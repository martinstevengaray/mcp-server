package com.mgaray.oktaapp.auth;

import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.mgaray.oktaapp.common.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.mgaray.oktaapp.auth.OktaDelegate.MCP_OAUTH_CALLBACK_PATH;

/**
 * OAuth 2.0 authorization-server proxy for MCP clients: makes this Lambda look
 * like the AS so Okta only ever sees <em>our</em> fixed redirect_uri, and we
 * stitch the client's real loopback callback back in. This honors any client's
 * redirect_uri without registering it in Okta and without a per-client app —
 * the pinned Native app ({@code OKTA_MCP_CLIENT_ID}, handed out by the /register
 * shim) is shared by all clients.
 *
 * <p>Three legs:
 * <ol>
 *   <li>{@code /authorize} — client hits us with its loopback redirect_uri + PKCE
 *       code_challenge; we redirect to Okta with our fixed callback and the
 *       client's redirect_uri/state smuggled into a signed {@code state}.</li>
 *   <li>{@code /oauth/callback} — Okta returns here; we redirect to the client's
 *       loopback callback with Okta's code and the client's original state.</li>
 *   <li>{@code /token} — client posts the code + its PKCE code_verifier; we
 *       forward to Okta swapping in our fixed redirect_uri (what the code was
 *       bound to) and return Okta's token response verbatim.</li>
 * </ol>
 *
 * <p>PKCE stays end-to-end between the client and Okta (the code_verifier merely
 * passes through us). The two guards on the redirect-bearing {@code /callback}
 * are an HMAC signature over the state and a loopback-only allowlist on the
 * client redirect_uri — together they close the open-redirect / code-exfiltration
 * hole inherent to a stitching proxy.
 */
class McpOAuthProxy {

    private final String oktaIssuer;
    private final byte[] stateSigningKey;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    McpOAuthProxy(String oktaIssuer, String stateSigningKey) {
        this.oktaIssuer = oktaIssuer;
        this.stateSigningKey = stateSigningKey.getBytes(StandardCharsets.UTF_8);
    }

    // ---- /authorize : redirect the client to Okta under our own redirect_uri ----

    Map<String, Object> handleAuthorize(Map<String, Object> event) {
        Map<String, String> params = parseUrlEncoded(rawQueryString(event));
        String clientRedirectUri = params.get("redirect_uri");
        String clientState = params.get("state");
        if (clientRedirectUri == null || !isLoopback(clientRedirectUri)) {
            return HttpUtils.htmlError(400, "redirect_uri must be a loopback address");
        }
        // Forward every client param to Okta, but swap in our callback + a signed
        // state carrying where to send the client back. Unknown params (resource,
        // nonce, …) pass through untouched so we don't have to enumerate them.
        params.put("redirect_uri", ourCallbackUri(event));
        params.put("state", signState(clientRedirectUri, clientState));
        params.putIfAbsent("response_type", "code");
        String authorizeUrl = oktaIssuer + "/v1/authorize?" + toUrlEncoded(params);
        return HttpUtils.response(302, Map.of("location", authorizeUrl), "");
    }

    // ---- /oauth/callback : hand Okta's code back to the client's loopback URI ----

    Map<String, Object> handleCallback(Map<String, Object> event, Logger logger) {
        String state = queryParam(event, "state");
        ClientReturn target;
        try {
            target = verifyState(state);
        } catch (Exception e) {
            logger.log("MCP proxy callback rejected: " + e.getMessage());
            return HttpUtils.htmlError(400, "Invalid or tampered authorization state");
        }
        if (!isLoopback(target.redirectUri())) {
            return HttpUtils.htmlError(400, "Refusing to redirect to a non-loopback address");
        }
        StringBuilder location = new StringBuilder(target.redirectUri());
        char sep = target.redirectUri().indexOf('?') >= 0 ? '&' : '?';
        String error = queryParam(event, "error");
        if (error != null) {
            location.append(sep).append("error=").append(HttpUtils.urlEncode(error));
            String description = queryParam(event, "error_description");
            if (description != null) {
                location.append("&error_description=").append(HttpUtils.urlEncode(description));
            }
        } else {
            String code = queryParam(event, "code");
            if (code == null) {
                return HttpUtils.htmlError(400, "Okta returned no authorization code");
            }
            location.append(sep).append("code=").append(HttpUtils.urlEncode(code));
        }
        if (target.state() != null) {
            location.append("&state=").append(HttpUtils.urlEncode(target.state()));
        }
        return HttpUtils.response(302, Map.of("location", location.toString()), "");
    }

    // ---- /token : forward to Okta, swapping the client's redirect_uri for ours ----

    Map<String, Object> handleToken(Map<String, Object> event, Logger logger) {
        Map<String, String> form = parseUrlEncoded(readBody(event));
        // The code was bound to our callback at /authorize, so the exchange must
        // present that same redirect_uri — not the client's loopback one.
        if ("authorization_code".equals(form.get("grant_type"))) {
            form.put("redirect_uri", ourCallbackUri(event));
        }
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(oktaIssuer + "/v1/token"))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toUrlEncoded(form)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.log("MCP proxy token exchange failed: " + e);
            return HttpUtils.responseJson(502, JsonUtils.toString(Map.of(
                    "error", "server_error",
                    "error_description", "token exchange with Okta failed")));
        }
        // Pass Okta's response (success or OAuth error) straight back to the client.
        return HttpUtils.responseJson(response.statusCode(), response.body());
    }

    // ---- signed state ----

    private record ClientReturn(String redirectUri, String state) {}

    // state = base64url(JSON{ru, cs}) + "." + base64url(HMAC-SHA256(payload)).
    private String signState(String clientRedirectUri, String clientState) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ru", clientRedirectUri);
        if (clientState != null) {
            payload.put("cs", clientState);
        }
        String encoded = HttpUtils.base64Url(JsonUtils.toString(payload).getBytes(StandardCharsets.UTF_8));
        return encoded + "." + HttpUtils.base64Url(hmac(encoded));
    }

    private ClientReturn verifyState(String state) {
        if (state == null) {
            throw new IllegalArgumentException("missing state");
        }
        int dot = state.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("malformed state");
        }
        String encoded = state.substring(0, dot);
        byte[] presented = Base64.getUrlDecoder().decode(state.substring(dot + 1));
        if (!MessageDigest.isEqual(hmac(encoded), presented)) {
            throw new SecurityException("bad state signature");
        }
        Map<String, Object> payload = JsonUtils.parse(
                new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        Object ru = payload.get("ru");
        if (!(ru instanceof String redirectUri) || redirectUri.isBlank()) {
            throw new IllegalArgumentException("state missing redirect_uri");
        }
        Object cs = payload.get("cs");
        return new ClientReturn(redirectUri, cs instanceof String s ? s : null);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stateSigningKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC of proxy state failed", e);
        }
    }

    // ---- helpers ----

    private static String ourCallbackUri(Map<String, Object> event) {
        String domainName = JsonUtils.getNestedField(event, "requestContext", "domainName");
        return "https://" + domainName + MCP_OAUTH_CALLBACK_PATH;
    }

    // Loopback per RFC 8252: the only redirect targets a native MCP client should use.
    private static boolean isLoopback(String redirectUri) {
        try {
            String host = URI.create(redirectUri).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            return host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("[::1]") || host.equals("::1");
        } catch (Exception e) {
            return false;
        }
    }

    private static String rawQueryString(Map<String, Object> event) {
        return event.get("rawQueryString") instanceof String q ? q : "";
    }

    private static String queryParam(Map<String, Object> event, String name) {
        return JsonUtils.getNestedField(event, "queryStringParameters", name);
    }

    private static String readBody(Map<String, Object> event) {
        String body = event.get("body") instanceof String s ? s : "";
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return body;
    }

    private static Map<String, String> parseUrlEncoded(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String toUrlEncoded(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(HttpUtils.urlEncode(entry.getKey()))
              .append('=')
              .append(HttpUtils.urlEncode(entry.getValue()));
        }
        return sb.toString();
    }
}

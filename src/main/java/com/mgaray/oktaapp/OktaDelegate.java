package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OktaDelegate {

    private static final String OKTA_TOKEN_COOKIE = "okta_token";
    private static final String OATH_STATE_COOKIE = "oauth_state";
    private static final String CALLBACK_PATH = "/callback";
    private static final String REGISTER_PATH = "/register";
    private static final Map<String, String> JSON_HEADERS = Map.of("content-type", "application/json");

    private final String oktaIssuer;
    private final String oktaWebClientId;
    private final String oktaWebClientSecret;
    private final String oktaScopes;
    // Pre-registered Okta *Native* app (public client, PKCE) the DCR shim hands to
    // MCP clients so they never attempt real (anonymous) registration against Okta.
    private final String oktaMcpClientId;
    private final AccessTokenVerifier verifier;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom;

    public OktaDelegate(String oktaIssuer,
                        String oktaAudience,
                        String oktaWebClientId,
                        String oktaWebClientSecret,
                        String oktaScopes,
                        String oktaMcpClientId) {
        this.oktaIssuer = oktaIssuer;
        this.oktaWebClientId = oktaWebClientId;
        this.oktaWebClientSecret = oktaWebClientSecret;
        this.oktaScopes = oktaScopes;
        this.oktaMcpClientId = oktaMcpClientId;
        this.verifier = JwtVerifiers.accessTokenVerifierBuilder()
                .setIssuer(oktaIssuer)
                .setAudience(oktaAudience)
                .setConnectionTimeout(Duration.ofSeconds(5))
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.secureRandom = new SecureRandom();
    }

    public Jwt readJwt(Map<String, Object> event) throws JwtVerificationException {
        String token = readBearerToken(event);
        if (token == null) {
            token = readCookieValue(event, OKTA_TOKEN_COOKIE);
        }
        return verifier.decode(token);
    }

    // True when a pre-registered MCP (Native) client id is configured, turning on the
    // DCR shim (/register). When off, clients must be told a static client_id and
    // discovery points straight at the Okta issuer.
    public boolean dcrShimEnabled() {
        return oktaMcpClientId != null && !oktaMcpClientId.isBlank();
    }

    // RFC 9728 Protected Resource Metadata: tells an MCP client which authorization
    // server guards the /mcp resource. The 401 from /mcp points here. With the shim on
    // we advertise *ourselves* as the AS so clients discover our /register; otherwise
    // we point straight at Okta.
    public Map<String, Object> protectedResourceMetadata(String domainName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", "https://" + domainName + "/mcp");
        String authServer = dcrShimEnabled() ? "https://" + domainName : oktaIssuer;
        metadata.put("authorization_servers", List.of(authServer));
        // Tells the client which scopes to request at /authorize; without this an
        // Okta AS with no default scopes rejects the call ('scope' must be provided).
        List<String> scopes = scopesList();
        if (!scopes.isEmpty()) {
            metadata.put("scopes_supported", scopes);
        }
        return metadata;
    }

    private List<String> scopesList() {
        if (oktaScopes == null || oktaScopes.isBlank()) {
            return List.of();
        }
        return List.of(oktaScopes.trim().split("\\s+"));
    }

    // RFC 8414 Authorization Server Metadata. With the shim on we present ourselves as
    // the issuer and advertise our own /register, while still delegating
    // authorize/token/keys to Okta — so a DCR-only MCP client can "register" (and get
    // our pre-registered Native client_id back) even though Okta has no anonymous DCR.
    // With the shim off we simply mirror Okta and omit registration.
    public Map<String, Object> authorizationServerMetadata(String domainName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", dcrShimEnabled() ? "https://" + domainName : oktaIssuer);
        metadata.put("authorization_endpoint", oktaIssuer + "/v1/authorize");
        metadata.put("token_endpoint", oktaIssuer + "/v1/token");
        metadata.put("jwks_uri", oktaIssuer + "/v1/keys");
        if (dcrShimEnabled()) {
            metadata.put("registration_endpoint", "https://" + domainName + REGISTER_PATH);
        }
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported",
                List.of("authorization_code", "refresh_token", "client_credentials"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported",
                List.of("client_secret_basic", "client_secret_post", "none"));
        List<String> scopes = scopesList();
        if (!scopes.isEmpty()) {
            metadata.put("scopes_supported", scopes);
        }
        return metadata;
    }

    // DCR shim: satisfies a client's RFC 7591 registration POST without calling Okta.
    // It ignores the requested credentials and hands back our one pre-registered Native
    // app's client_id (public client => token_endpoint_auth_method "none"), echoing the
    // client's own metadata so its local validation is satisfied. NOTE: that Native app
    // must already have each client's redirect_uri registered in Okta — the shim can't
    // add them (that was the real proxy's job).
    public Map<String, Object> registerClient(String requestBody) {
        if (!dcrShimEnabled()) {
            return HttpUtils.response(404, JSON_HEADERS, JsonUtils.toString(Map.of("error", "not_found")));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", oktaMcpClientId);
        response.put("token_endpoint_auth_method", "none");
        try {
            Map<String, Object> request = JsonUtils.parse(requestBody);
            for (String field : List.of("redirect_uris", "grant_types", "response_types",
                    "scope", "client_name")) {
                Object value = request.get(field);
                if (value != null) {
                    response.put(field, value);
                }
            }
        } catch (Exception ignored) {
            // Body absent or unparseable — client_id alone is a valid RFC 7591 response.
        }
        return HttpUtils.response(201, JSON_HEADERS, JsonUtils.toString(response));
    }

    public Map<String, Object> authenticationRedirects(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        if (!CALLBACK_PATH.equals(path)) {
            return redirectToOkta(event, path);
        }
        return callback(event, context);
    }

    private String readBearerToken(Map<String, Object> event) {
        for (Map.Entry<String, Object> entry : JsonUtils.getNestedMap(event, "headers").entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() instanceof String s
                    && s.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return s.substring(7).trim();
            }
        }
        return null;
    }

    private String readCookieValue(Map<String, Object> event, String cookieName) {
        if (event.get("cookies") instanceof List<?> cookies) {
            for (Object cookie : cookies) {
                if (cookie instanceof String s && s.startsWith(cookieName + "=")) {
                    return s.substring(cookieName.length() + 1);
                }
            }
        }
        return null;
    }

    // Redirects browser to Okta, remembering where it wanted to go in the state cookie.
    private Map<String, Object> redirectToOkta(Map<String, Object> event, String path) {
        byte[] randomTokenBytes = new byte[24];
        secureRandom.nextBytes(randomTokenBytes);
        String state = base64Url(randomTokenBytes);
        String rawQuery = event.get("rawQueryString") instanceof String q && !q.isEmpty() ? "?" + q : "";
        String original = base64Url((path + rawQuery).getBytes(StandardCharsets.UTF_8));
        String domainName = JsonUtils.getNestedField(event,"requestContext", "domainName");
        String redirectUri = "https://" + domainName + CALLBACK_PATH;
        String authorizeUrl = this.oktaIssuer + "/v1/authorize"
                + "?client_id=" + HttpUtils.urlEncode(oktaWebClientId)
                + "&response_type=code"
                + "&scope=" + HttpUtils.urlEncode(oktaScopes)
                + "&redirect_uri=" + HttpUtils.urlEncode(redirectUri)
                + "&state=" + state;
        return HttpUtils.response(302, Map.of("location", authorizeUrl), "",
                List.of(OATH_STATE_COOKIE + "=" + state + "." + original
                        + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300"));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Exchanges the authorization code for an access token, stores it in a session cookie, then redirect back to self.
    private Map<String, Object> callback(Map<String, Object> event, Context context) {
        final String error = JsonUtils.getNestedField(event, "queryStringParameters", "error");
        if (error != null) {
            String errorDescription = JsonUtils.getNestedField(event, "queryStringParameters", "error_description");
            context.getLogger().log("Okta sign-in failed: " + error + " — " + errorDescription);
            return HttpUtils.htmlError(400, "Okta sign-in failed");
        }
        final String code = JsonUtils.getNestedField(event, "queryStringParameters", "code");
        final String state = JsonUtils.getNestedField(event, "queryStringParameters", "state");
        final String oathStateCookie = readCookieValue(event, OATH_STATE_COOKIE);
        if (code == null || state == null || oathStateCookie == null || !oathStateCookie.startsWith(state + ".")) {
            return HttpUtils.htmlError(400, "Login state mismatch, retry.");
        }
        //verify "code" in queryStringParameters to retrieve an accessToken for client
        final String domainName = JsonUtils.getNestedField(event,"requestContext", "domainName");
        final String redirectUri = "https://" + domainName + CALLBACK_PATH;
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(oktaIssuer + "/v1/token"))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (oktaWebClientId + ":" + oktaWebClientSecret).getBytes(StandardCharsets.UTF_8)))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "grant_type=authorization_code"
                                    + "&code=" + HttpUtils.urlEncode(code)
                                    + "&redirect_uri=" + HttpUtils.urlEncode(redirectUri)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                context.getLogger().log("token exchange failed: " + response.body());
                return HttpUtils.htmlError(502, "Token exchange with Okta failed");
            }
        } catch (Exception e) {
            context.getLogger().log("token exchange failed: " + e);
            return HttpUtils.htmlError(502, "Could not reach Okta to complete sign-in.");
        }
        String accessToken = JsonUtils.getNestedField(response.body(), "access_token");
        try {
            verifier.decode(accessToken); // Verify once before trusting the cookie to avoid a redirect loop
        } catch (JwtVerificationException e) {
            context.getLogger().log("token from Okta failed verification: " + e.getMessage());
            return HttpUtils.htmlError(500, "Okta issued a token this service could not verify.");
        }
        String originallyRequestedUrl = new String(
                Base64.getUrlDecoder().decode(oathStateCookie.substring(state.length() + 1)),
                StandardCharsets.UTF_8);
        Integer maxAge =  JsonUtils.getNestedField(response.body(), "expires_in");
        return HttpUtils.response(302, Map.of("location", originallyRequestedUrl), "", List.of(
                OKTA_TOKEN_COOKIE + "=" + accessToken + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + maxAge,
                OATH_STATE_COOKIE + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0")); //clear out oath cookie
    }

}

package com.mgaray.oktaapp.auth;

import com.mgaray.oktaapp.common.Logger;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.JwtVerificationException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.mgaray.oktaapp.auth.OktaDelegate.CALLBACK_PATH;
import static com.mgaray.oktaapp.auth.OktaDelegate.OKTA_TOKEN_COOKIE;

class AuthenticationHandlerWeb {

    private static final String OAUTH_STATE_COOKIE = "oauth_state";

    private final String oktaIssuer;
    private final String oktaWebClientId;
    private final String oktaWebClientSecret;
    private final String oktaScopes;

    private final AccessTokenVerifier verifier;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom;

    AuthenticationHandlerWeb(String oktaIssuer,
                                    String oktaWebClientId,
                                    String oktaWebClientSecret,
                                    String oktaScopes,
                                    AccessTokenVerifier verifier) {
        this.oktaIssuer = oktaIssuer;
        this.oktaWebClientId = oktaWebClientId;
        this.oktaWebClientSecret = oktaWebClientSecret;
        this.oktaScopes = oktaScopes;
        this.verifier = verifier;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.secureRandom = new SecureRandom();
    }

    Map<String, Object> authenticationRedirectWeb(Map<String, Object> event) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        byte[] randomTokenBytes = new byte[24];
        secureRandom.nextBytes(randomTokenBytes);
        String state = HttpUtils.base64Url(randomTokenBytes);
        // PKCE: challenge goes to Okta now, verifier rides the state cookie until /callback.
        byte[] verifierBytes = new byte[32];
        secureRandom.nextBytes(verifierBytes);
        String codeVerifier = HttpUtils.base64Url(verifierBytes);
        String codeChallenge = HttpUtils.base64Url(sha256(codeVerifier));
        String rawQuery = event.get("rawQueryString") instanceof String q && !q.isEmpty() ? "?" + q : "";
        String original = HttpUtils.base64Url((path + rawQuery).getBytes(StandardCharsets.UTF_8));
        String domainName = JsonUtils.getNestedField(event,"requestContext", "domainName");
        String redirectUri = "https://" + domainName + CALLBACK_PATH;
        String authorizeUrl = this.oktaIssuer + "/v1/authorize"
                + "?client_id=" + HttpUtils.urlEncode(oktaWebClientId)
                + "&response_type=code"
                + "&scope=" + HttpUtils.urlEncode(oktaScopes)
                + "&redirect_uri=" + HttpUtils.urlEncode(redirectUri)
                + "&state=" + state
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";
        return HttpUtils.response(302, Map.of("location", authorizeUrl), "",
                List.of(OAUTH_STATE_COOKIE + "=" + state + "." + codeVerifier + "." + original
                        + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=300"));
    }

    // Exchanges the authorization code for an access token, stores it in a session cookie, then redirect back to self.
    Map<String, Object> handleCallback(Map<String, Object> event, Logger logger) {
        final String error = JsonUtils.getNestedField(event, "queryStringParameters", "error");
        if (error != null) {
            String errorDescription = JsonUtils.getNestedField(event, "queryStringParameters", "error_description");
            logger.log("Okta sign-in failed: " + error + " — " + errorDescription);
            return HttpUtils.htmlError(400, "Okta sign-in failed");
        }
        final String code = JsonUtils.getNestedField(event, "queryStringParameters", "code");
        final String state = JsonUtils.getNestedField(event, "queryStringParameters", "state");
        final String oathStateCookie = HttpUtils.readCookieValue(event, OAUTH_STATE_COOKIE);
        if (code == null || state == null || oathStateCookie == null || !oathStateCookie.startsWith(state + ".")) {
            return HttpUtils.htmlError(400, "Login state mismatch, retry.");
        }
        // Cookie layout: state.codeVerifier.original (all base64url, so dot-safe).
        String[] cookieParts = oathStateCookie.split("\\.", 3);
        if (cookieParts.length != 3) {
            return HttpUtils.htmlError(400, "Login state mismatch, retry.");
        }
        final String codeVerifier = cookieParts[1];
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
                                    + "&redirect_uri=" + HttpUtils.urlEncode(redirectUri)
                                    + "&code_verifier=" + HttpUtils.urlEncode(codeVerifier)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.log("token exchange failed: " + response.body());
                return HttpUtils.htmlError(502, "Token exchange with Okta failed");
            }
        } catch (Exception e) {
            logger.log("token exchange failed: " + e);
            return HttpUtils.htmlError(502, "Could not reach Okta to complete sign-in.");
        }
        String accessToken = JsonUtils.getNestedField(response.body(), "access_token");
        try {
            verifier.decode(accessToken); // Verify once before trusting the cookie to avoid a redirect loop
        } catch (JwtVerificationException e) {
            logger.log("token from Okta failed verification: " + e.getMessage());
            return HttpUtils.htmlError(500, "Okta issued a token this service could not verify.");
        }
        String originallyRequestedUrl = new String(
                Base64.getUrlDecoder().decode(cookieParts[2]),
                StandardCharsets.UTF_8);
        Integer maxAge =  JsonUtils.getNestedField(response.body(), "expires_in");
        return HttpUtils.response(302, Map.of("location", originallyRequestedUrl), "", List.of(
                OKTA_TOKEN_COOKIE + "=" + accessToken + "; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=" + maxAge,
                OAUTH_STATE_COOKIE + "=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0")); //clear out oath cookie
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}

package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.JwtVerificationException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class OktaWebDelegate {

    private static final String OKTA_TOKEN_COOKIE = "okta_token";
    private static final String OATH_STATE_COOKIE = "oauth_state";
    public static final String CALLBACK_PATH = "/callback";

    private final String oktaIssuer;
    private final String oktaWebClientId;
    private final String oktaWebClientSecret;
    private final String oktaScopes;

    private final AccessTokenVerifier verifier;
    private final HttpClient httpClient;
    private final SecureRandom secureRandom;

    public OktaWebDelegate(String oktaIssuer,
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

    public Map<String, Object> authenticationRedirectWeb(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
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
    public Map<String, Object> handleCallback(Map<String, Object> event, Context context) {
        final String error = JsonUtils.getNestedField(event, "queryStringParameters", "error");
        if (error != null) {
            String errorDescription = JsonUtils.getNestedField(event, "queryStringParameters", "error_description");
            context.getLogger().log("Okta sign-in failed: " + error + " — " + errorDescription);
            return HttpUtils.htmlError(400, "Okta sign-in failed");
        }
        final String code = JsonUtils.getNestedField(event, "queryStringParameters", "code");
        final String state = JsonUtils.getNestedField(event, "queryStringParameters", "state");
        final String oathStateCookie = HttpUtils.readCookieValue(event, OATH_STATE_COOKIE);
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

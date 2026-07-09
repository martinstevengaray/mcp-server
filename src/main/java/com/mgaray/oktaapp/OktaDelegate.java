package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.time.Duration;
import java.util.Map;

public class OktaDelegate {

    private static final String OKTA_TOKEN_COOKIE = "okta_token";
    private final AccessTokenVerifier verifier;

    private final OktaMcpDelegate oktaMcpDelegate;
    private final OktaWebDelegate oktaWebDelegate;

    public OktaDelegate(String oktaIssuer,
                        String oktaAudience,
                        String oktaWebClientId,
                        String oktaWebClientSecret,
                        String oktaScopes,
                        String oktaMcpClientId) {
        this.verifier = JwtVerifiers.accessTokenVerifierBuilder()
                .setIssuer(oktaIssuer)
                .setAudience(oktaAudience)
                .setConnectionTimeout(Duration.ofSeconds(5))
                .build();
        this.oktaMcpDelegate = new OktaMcpDelegate(oktaIssuer, oktaScopes, oktaMcpClientId);
        this.oktaWebDelegate = new OktaWebDelegate(oktaIssuer, oktaWebClientId, oktaWebClientSecret, oktaScopes, verifier);
    }

    public Jwt readJwt(Map<String, Object> event) throws JwtVerificationException {
        String token = readBearerToken(event);
        if (token == null) {
            token = HttpUtils.readCookieValue(event, OKTA_TOKEN_COOKIE);
        }
        return verifier.decode(token);
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

    public Map<String, Object> authenticationRedirectMcp(Map<String, Object> event) { //to support mcp clients
        return this.oktaMcpDelegate.authenticationRedirectMcp(event);
    }

    public Map<String, Object> handleWellKnown(String path, Map<String, Object> event) {
        return this.oktaMcpDelegate.handleWellKnown(path, event);
    }

    public Map<String, Object> handleRegister(Map<String, Object> event) {
        return this.oktaMcpDelegate.handleRegister(event);
    }

    public Map<String, Object> authenticationRedirectWeb(Map<String, Object> event, Context context) {
        return this.oktaWebDelegate.authenticationRedirectWeb(event, context);
    }

    public Map<String, Object> handleCallback(Map<String, Object> event, Context context) {
        return this.oktaWebDelegate.handleCallback(event, context);
    }


}

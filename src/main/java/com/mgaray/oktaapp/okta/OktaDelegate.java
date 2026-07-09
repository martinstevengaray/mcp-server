package com.mgaray.oktaapp.okta;

import com.mgaray.oktaapp.Logger.Logger;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.time.Duration;
import java.util.Map;

import static com.mgaray.oktaapp.OktaAppLambda.*;

public class OktaDelegate {

    private static final String PROTECTED_RESOURCE_METADATA_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX = "/.well-known/oauth-authorization-server";
    private static final String PROTECTED_RESOURCE_METADATA_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX = "/.well-known/openid-configuration";

    private static final String OKTA_TOKEN_COOKIE = "okta_token";
    private final AccessTokenVerifier verifier;

    private final McpAuthenticationHandler mcpAuthenticationHandler;
    private final WebAuthenticationHandler webAuthenticationHandler;

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
        this.mcpAuthenticationHandler = new McpAuthenticationHandler(oktaIssuer, oktaScopes, oktaMcpClientId);
        this.webAuthenticationHandler = new WebAuthenticationHandler(oktaIssuer, oktaWebClientId, oktaWebClientSecret, oktaScopes, verifier);
    }

    public Jwt readJwt(Map<String, Object> event) throws JwtVerificationException {
        String token = readBearerToken(event);
        if (token == null) {
            token = HttpUtils.readCookieValue(event, OKTA_TOKEN_COOKIE);
        }
        return verifier.decode(token);
    }

    public boolean isPathPublic(String path) {
        return (path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH_PREFIX) ||
                path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX) ||
                path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX) ||
                REGISTER_PATH.equals(path) ||
                CALLBACK_PATH.equals(path));
    }

    public Map<String, Object> handlePublicPath(String path, Map<String, Object> event, Logger logger) {
        if (path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH_PREFIX)) {
            return mcpAuthenticationHandler.handleOauthProtectedResource(event);
        }
        if (path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX) ||
                path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX)) {
            return mcpAuthenticationHandler.handleOauthAuthorizationServer(event);
        }
        if (REGISTER_PATH.equals(path)) {
            return mcpAuthenticationHandler.handleRegister(event);
        }
        if (CALLBACK_PATH.equals(path)) {
            return webAuthenticationHandler.handleCallback(event, logger);
        }
        throw new IllegalStateException("Unsupported path: " + path);
    }

    public Map<String, Object> authenticationRedirectMcp(Map<String, Object> event) { //to support mcp clients
        return this.mcpAuthenticationHandler.authenticationRedirectMcp(event);
    }

    public Map<String, Object> authenticationRedirectWeb(Map<String, Object> event) {
        return this.webAuthenticationHandler.authenticationRedirectWeb(event);
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

}

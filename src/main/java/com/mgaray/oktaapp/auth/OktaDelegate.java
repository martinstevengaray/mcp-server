package com.mgaray.oktaapp.auth;

import com.mgaray.oktaapp.common.Logger;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.AccessTokenVerifier;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;
import com.okta.jwt.JwtVerifiers;

import java.time.Duration;
import java.util.Map;

public class OktaDelegate {

    static final String OKTA_TOKEN_COOKIE = "okta_token";
    static final String REGISTER_PATH = "/register";
    static final String CALLBACK_PATH = "/callback";
    // MCP OAuth proxy: this Lambda fronts Okta's authorize/token so it can honor
    // each MCP client's own loopback redirect_uri without registering it in Okta.
    static final String AUTHORIZE_PATH = "/authorize";
    static final String TOKEN_PATH = "/token";
    static final String MCP_OAUTH_CALLBACK_PATH = "/oauth/callback";
    static final String WELL_KNOWN_OAUTH_PROTECTED_RESOURCE_PATH_PREFIX = "/.well-known/oauth-protected-resource";
    private static final String WELL_KNOWN_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX = "/.well-known/oauth-authorization-server";
    private static final String WELL_KNOWN_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX = "/.well-known/openid-configuration";

    private final AccessTokenVerifier verifier;

    private final AuthenticationHandlerMcp authenticationHandlerMcp;
    private final AuthenticationHandlerWeb authenticationHandlerWeb;
    private final McpOAuthProxy mcpOAuthProxy;

    public OktaDelegate(String oktaIssuer,
                        String oktaAudience,
                        String oktaWebClientId,
                        String oktaWebClientSecret,
                        String oktaScopes,
                        String oktaMcpClientId,
                        String symmetricSigningKey) {
        this.verifier = JwtVerifiers.accessTokenVerifierBuilder()
                .setIssuer(oktaIssuer)
                .setAudience(oktaAudience)
                .setConnectionTimeout(Duration.ofSeconds(5))
                .build();
        this.mcpOAuthProxy = new McpOAuthProxy(oktaIssuer, symmetricSigningKey);
        this.authenticationHandlerMcp = new AuthenticationHandlerMcp(oktaIssuer, oktaScopes, oktaMcpClientId);
        this.authenticationHandlerWeb = new AuthenticationHandlerWeb(oktaIssuer, oktaWebClientId, oktaWebClientSecret, oktaScopes, verifier);
    }

    public Jwt readJwt(Map<String, Object> event) throws JwtVerificationException {
        String token = readBearerToken(event);
        if (token == null) {
            token = HttpUtils.readCookieValue(event, OKTA_TOKEN_COOKIE);
        }
        return verifier.decode(token);
    }

    public boolean isPublicPath(String path) {
        return (path.startsWith(WELL_KNOWN_OAUTH_PROTECTED_RESOURCE_PATH_PREFIX) ||
                path.startsWith(WELL_KNOWN_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX) ||
                path.startsWith(WELL_KNOWN_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX) ||
                REGISTER_PATH.equals(path) ||
                CALLBACK_PATH.equals(path) ||
                AUTHORIZE_PATH.equals(path) ||
                TOKEN_PATH.equals(path) ||
                MCP_OAUTH_CALLBACK_PATH.equals(path));
    }

    public Map<String, Object> handlePublicPath(String path, Map<String, Object> event, Logger logger) {
        if (path.startsWith(WELL_KNOWN_OAUTH_PROTECTED_RESOURCE_PATH_PREFIX)) {
            return authenticationHandlerMcp.handleOauthProtectedResource(event);
        }
        if (path.startsWith(WELL_KNOWN_OAUTH_AUTHORIZATION_SERVER_PATH_PREFIX) ||
                path.startsWith(WELL_KNOWN_OAUTH_OPENID_CONFIGURATION_PATH_PREFIX)) {
            return authenticationHandlerMcp.handleOauthAuthorizationServer(event);
        }
        if (REGISTER_PATH.equals(path)) {
            return authenticationHandlerMcp.handleRegister(event);
        }
        if (CALLBACK_PATH.equals(path)) {
            return authenticationHandlerWeb.handleCallback(event, logger);
        }
        if (AUTHORIZE_PATH.equals(path)) {
            return mcpOAuthProxy.handleAuthorize(event);
        }
        if (TOKEN_PATH.equals(path)) {
            return mcpOAuthProxy.handleToken(event, logger);
        }
        if (MCP_OAUTH_CALLBACK_PATH.equals(path)) {
            return mcpOAuthProxy.handleCallback(event, logger);
        }
        throw new IllegalStateException("Unsupported path: " + path);
    }

    public Map<String, Object> authenticationRedirectMcp(Map<String, Object> event) { //to support mcp clients
        return this.authenticationHandlerMcp.authenticationRedirectMcp(event);
    }

    public Map<String, Object> authenticationRedirectWeb(Map<String, Object> event) {
        return this.authenticationHandlerWeb.authenticationRedirectWeb(event);
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

package com.mgaray.oktaapp;

import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OktaMcpDelegate {

    private static final String REGISTER_PATH = "/register";
    private static final String PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource";
    private static final Map<String, String> JSON_HEADERS = Map.of("content-type", "application/json");
    private static final String AUTHORIZATION_SERVER = "oauth-authorization-server";
    private static final String OPENID_CONFIGURATION = "openid-configuration";

    private final String oktaIssuer;
    private final String oktaScopes;
    // Pre-registered Okta *Native* app (public client, PKCE) the DCR shim hands to
    // MCP clients so they never attempt real (anonymous) registration against Okta.
    private final String oktaMcpClientId;

    public OktaMcpDelegate(String oktaIssuer,
                        String oktaScopes,
                        String oktaMcpClientId) {
        this.oktaIssuer = oktaIssuer;
        this.oktaScopes = oktaScopes;
        this.oktaMcpClientId = oktaMcpClientId;
    }

    public Map<String, Object> authenticationRedirectMcp(Map<String, Object> event) { //to support mcp clients
        String domainName = JsonUtils.getNestedField(event, "requestContext", "domainName");
        String wwwAuthenticate = "Bearer resource_metadata=\"https://" + domainName
                + PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH + "\"";
        return HttpUtils.response(401,
                Map.of("content-type", "application/json", "www-authenticate", wwwAuthenticate),
                JsonUtils.toString(Map.of(
                        "error", "unauthorized",
                        "message", "A valid Okta bearer token is required")));
    }

    public Map<String, Object> handleWellKnown(String path, Map<String, Object> event) {
        String domainName = JsonUtils.getNestedField(event, "requestContext", "domainName");
        Map<String, String> jsonHeaders = Map.of("content-type", "application/json");
        if (path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH)) {
            return HttpUtils.response(200, jsonHeaders,
                    JsonUtils.toString(protectedResourceMetadata(domainName)));
        }
        // endpoints '/.well-known/oauth-authorization-server' and '/.well-known/openid-configuration'
        // both describe the Authorization Server.
        if (path.contains(AUTHORIZATION_SERVER) || path.contains(OPENID_CONFIGURATION)) {
            return HttpUtils.response(200, jsonHeaders,
                    JsonUtils.toString(authorizationServerMetadata(domainName)));
        }
        return HttpUtils.response(404, jsonHeaders,
                JsonUtils.toString(Map.of("error", "not_found")));
    }

    // RFC 9728 Protected Resource Metadata: tells an MCP client which authorization
    // server guards the /mcp resource. The 401 from /mcp points here. With the shim on
    // we advertise *ourselves* as the AS so clients discover our /register; otherwise
    // we point straight at Okta.
    private Map<String, Object> protectedResourceMetadata(String domainName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", "https://" + domainName + "/mcp");
        String authServer = "https://" + domainName; //alternatively 'authServer = oktaIssuer;'
        metadata.put("authorization_servers", List.of(authServer));
        // Tells the client which scopes to request at /authorize; without this an
        // Okta AS with no default scopes rejects the call ('scope' must be provided).
        metadata.put("scopes_supported", scopesList());
        return metadata;
    }

    private List<String> scopesList() {
        return List.of(oktaScopes.trim().split("\\s+"));
    }

    // RFC 8414 Authorization Server Metadata. With the shim on we present ourselves as
    // the issuer and advertise our own /register, while still delegating
    // authorize/token/keys to Okta — so a DCR-only MCP client can "register" (and get
    // our pre-registered Native client_id back) even though Okta has no anonymous DCR.
    // With the shim off we simply mirror Okta and omit registration.
    private Map<String, Object> authorizationServerMetadata(String domainName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", "https://" + domainName); // alternatively  oktaIssuer
        metadata.put("authorization_endpoint", oktaIssuer + "/v1/authorize");
        metadata.put("token_endpoint", oktaIssuer + "/v1/token");
        metadata.put("jwks_uri", oktaIssuer + "/v1/keys");
        metadata.put("registration_endpoint", "https://" + domainName + REGISTER_PATH);
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
    public Map<String, Object> handleRegister(Map<String, Object> event) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", oktaMcpClientId);
        response.put("token_endpoint_auth_method", "none");
        try {
            Map<String, Object> request = HttpUtils.parseBase64EncodedBody(event);
            for (String field : List.of("redirect_uris", "grant_types", "response_types", "scope", "client_name")) {
                Object value = request.get(field);
                if (value != null) {
                    response.put(field, value);
                }
            }
        } catch (Exception ignored) {
            // do nothing, body absent or unparseable — client_id alone is a valid RFC 7591 response.
        }
        return HttpUtils.response(201, JSON_HEADERS, JsonUtils.toString(response));
    }


}

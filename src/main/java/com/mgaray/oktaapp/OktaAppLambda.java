package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mgaray.oktaapp.common.AwsServicesDelegate;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.mgaray.oktaapp.jira.JiraDelegate;
import com.mgaray.oktaapp.mcp.McpHandler;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String MCP_PATH = "/mcp";
    private static final String REGISTER_PATH = "/register";
    private static final String WELL_KNOWN_PREFIX = "/.well-known/";
    public static final String PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource";
    public static final String PROTECTED_RESOURCE_METADATA_OAUTH_AUTHORIZATION_SERVER_PATH = "/.well-known/oauth-authorization-server";
    public static final String PROTECTED_RESOURCE_METADATA_OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration";

    private final OktaDelegate oktaDelegate;
    private final McpHandler mcpHandler;

    public OktaAppLambda() throws Exception {
        String oktaIssuer = System.getenv("OKTA_ISSUER");
        String oktaAudience = System.getenv("OKTA_AUDIENCE");
        String oktaWebClientId = System.getenv("OKTA_WEB_CLIENT_ID");
        String oktaScopes = System.getenv("OKTA_SCOPES");
        String oktaWebClientSecretSsmParameterKey = System.getenv("OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY");
        String oktaWebClientSecret = AwsServicesDelegate.fetchSmmParameterValue(oktaWebClientSecretSsmParameterKey);
        String oktaMcpClientId = System.getenv("OKTA_MCP_CLIENT_ID");
        this.oktaDelegate = new OktaDelegate(oktaIssuer, oktaAudience, oktaWebClientId, oktaWebClientSecret, oktaScopes,
                oktaMcpClientId);
        String jiraEmail = System.getenv("JIRA_CLIENT_EMAIL");
        String jiraCloudId = System.getenv("JIRA_CLOUD_ID");
        String jiraToken = AwsServicesDelegate.fetchSmmParameterValue(System.getenv("JIRA_CLIENT_TOKEN_SSM_PARAMETER_KEY"));
        this.mcpHandler = new McpHandler(new JiraDelegate(jiraEmail, jiraToken, jiraCloudId));
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        if (path != null && path.startsWith(WELL_KNOWN_PREFIX)) {
            return oktaDelegate.handleWellKnown(path, event);
        }
        if (REGISTER_PATH.equals(path)) {
            return oktaDelegate.registerClient(event);
        }
        if (MCP_PATH.equals(path)) {
            try {
                Jwt jwt = oktaDelegate.readJwt(event);
                return mcpHandler.handle(event, jwt);
            } catch (JwtVerificationException e) {
                return oktaDelegate.authenticationRedirectNativeApp(event);
            }
        }
        Jwt jwt;
        try {
            jwt = oktaDelegate.readJwt(event);
            return createSuccessResponse(event, jwt, context);
        } catch (JwtVerificationException e) {
            return oktaDelegate.authenticationRedirects(event, context);
        }
    }

    // OAuth discovery endpoints an MCP client fetches during the auth handshake.
    // These MUST return JSON — routing them here keeps them out of the browser
    // redirect fallback, which would otherwise send back an Okta HTML login page.
//    private Map<String, Object> handleWellKnown(String path, Map<String, Object> event) {
//        String domainName = JsonUtils.getNestedField(event, "requestContext", "domainName");
//        Map<String, String> jsonHeaders = Map.of("content-type", "application/json");
//        if (path.startsWith(PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH)) {
//            return HttpUtils.response(200, jsonHeaders,
//                    JsonUtils.toString(oktaDelegate.protectedResourceMetadata(domainName)));
//        }
//        // endpoints '/.well-known/oauth-authorization-server' and '/.well-known/openid-configuration' both describe the AS.
//        if (path.contains("oauth-authorization-server") || path.contains("openid-configuration")) {
//            return HttpUtils.response(200, jsonHeaders,
//                    JsonUtils.toString(oktaDelegate.authorizationServerMetadata(domainName)));
//        }
//        return HttpUtils.response(404, jsonHeaders,
//                JsonUtils.toString(Map.of("error", "not_found")));
//    }

//    // Decodes the request body, honoring API Gateway / Function URL base64 encoding.
//    private static String readBody(Map<String, Object> event) {
//        String body = event.get("body") instanceof String s ? s : "";
//        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
//            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
//        }
//        return body;
//    }

    // MCP clients authenticate with an Okta Bearer token (client_credentials flow),
    // so a failed verification returns a JSON 401 rather than the browser redirect.
    // The www-authenticate header points at our RFC 9728 metadata so the client can
    // discover the Okta authorization server (see handleWellKnown).
//    private Map<String, Object> handleMcpRequest(Map<String, Object> event) {
//        try {
//            oktaDelegate.readJwt(event);
//        } catch (JwtVerificationException e) {
//            return oktaDelegate.authenticationRedirectNativeApp(event);
////            String domainName = JsonUtils.getNestedField(event, "requestContext", "domainName");
////            String wwwAuthenticate = "Bearer resource_metadata=\"https://" + domainName
////                    + PROTECTED_RESOURCE_METADATA_OAUTH_PROTECTED_RESOURCE_PATH + "\"";
////            return HttpUtils.response(401,
////                    Map.of("content-type", "application/json", "www-authenticate", wwwAuthenticate),
////                    JsonUtils.toString(Map.of(
////                            "error", "unauthorized",
////                            "message", "A valid Okta bearer token is required")));
//        }
//        return mcpHandler.handle(event);
//    }

    private Map<String, Object> createSuccessResponse(Map<String, Object> event, Jwt jwt, Context context) {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> http = JsonUtils.getNestedMap(event, "requestContext", "http");
        Map<String, Object> headers = JsonUtils.getNestedMap(event,  "headers");
        response.put("method", http.get("method"));
        response.put("path", http.get("path"));
        response.put("sourceIp", http.get("sourceIp"));
        response.put("userAgent", http.get("userAgent"));
        response.put("queryStringParameters", event.get("queryStringParameters"));
        response.put("headers", headers);
        response.put("body", event.get("body"));
        response.put("requestId", context.getAwsRequestId());
        response.put("jwtClaims", jwt.getClaims());
        return HttpUtils.response(200, Map.of("content-type", "application/json"),
                JsonUtils.toString(response));
    }

}

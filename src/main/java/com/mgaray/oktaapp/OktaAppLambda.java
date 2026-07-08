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

import java.util.LinkedHashMap;
import java.util.Map;

public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String MCP_PATH = "/mcp";

    private final OktaDelegate oktaDelegate;
    private final McpHandler mcpHandler;

    public OktaAppLambda() throws Exception {
        String oktaIssuer = System.getenv("OKTA_ISSUER");
        String oktaAudience = System.getenv("OKTA_AUDIENCE");
        String oktaWebClientId = System.getenv("OKTA_WEB_CLIENT_ID");
        String oktaScopes = System.getenv("OKTA_SCOPES");
        String oktaWebClientSecretSsmParameterKey = System.getenv("OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY");
        String oktaWebClientSecret = AwsServicesDelegate.fetchSmmParameterValue(oktaWebClientSecretSsmParameterKey);
        this.oktaDelegate = new OktaDelegate(oktaIssuer, oktaAudience, oktaWebClientId, oktaWebClientSecret, oktaScopes);

        String jiraEmail = System.getenv("JIRA_CLIENT_EMAIL");
        String jiraCloudId = System.getenv("JIRA_CLOUD_ID");
        String jiraToken = AwsServicesDelegate.fetchSmmParameterValue(System.getenv("JIRA_CLIENT_TOKEN_SSM_PARAMETER_KEY"));
        this.mcpHandler = new McpHandler(new JiraDelegate(jiraEmail, jiraToken, jiraCloudId));
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        if (MCP_PATH.equals(path)) {
            return handleMcpRequest(event);
        }
        Jwt jwt;
        try {
            jwt = oktaDelegate.readJwt(event);
            return createSuccessResponse(event, jwt, context);
        } catch (JwtVerificationException e) {
            return oktaDelegate.authenticationRedirects(event, context);
        }
    }

    // MCP clients authenticate with an Okta Bearer token (client_credentials flow),
    // so a failed verification returns a JSON 401 rather than the browser redirect.
    private Map<String, Object> handleMcpRequest(Map<String, Object> event) {
        try {
            oktaDelegate.readJwt(event);
        } catch (JwtVerificationException e) {
            return HttpUtils.response(401,
                    Map.of("content-type", "application/json", "www-authenticate", "Bearer"),
                    JsonUtils.toString(Map.of(
                            "error", "unauthorized",
                            "message", "A valid Okta bearer token is required")));
        }
        return mcpHandler.handle(event);
    }

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

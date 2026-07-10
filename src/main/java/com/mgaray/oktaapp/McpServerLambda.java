package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mgaray.oktaapp.common.Logger;
import com.mgaray.oktaapp.common.AwsServicesDelegate;
import com.mgaray.oktaapp.common.CommonUtils;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.mgaray.oktaapp.mcp.jira.JiraClient;
import com.mgaray.oktaapp.mcp.McpHandler;
import com.mgaray.oktaapp.auth.OktaDelegate;
import com.mgaray.oktaapp.web.WebHandler;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import java.util.*;

public class McpServerLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    public static final boolean DEBUG = true;

    private static final String MCP_PATH = "/mcp";

    private final OktaDelegate oktaDelegate;
    private final McpHandler mcpHandler;
    private final WebHandler webHandler;

    public McpServerLambda() {
        String oktaIssuer = System.getenv("OKTA_ISSUER");
        String oktaAudience = System.getenv("OKTA_AUDIENCE");
        String oktaWebClientId = System.getenv("OKTA_WEB_CLIENT_ID");
        String oktaScopes = System.getenv("OKTA_SCOPES");
        String oktaWebClientSecretSsmParameterKey = System.getenv("OKTA_WEB_CLIENT_SECRET_SSM_PARAMETER_KEY");
        String oktaWebClientSecret = AwsServicesDelegate.fetchSmmParameterValue(oktaWebClientSecretSsmParameterKey);
        String oktaMcpClientId = System.getenv("OKTA_MCP_CLIENT_ID");
        // Symmetric (HMAC) key for signing values that round-trip through third
        // parties — currently the MCP OAuth proxy's authorization `state`.
        String symmetricSigningKey = AwsServicesDelegate.fetchSmmParameterValue(
                System.getenv("SYMMETRIC_SIGNING_KEY_SSM_PARAMETER_KEY"));
        this.oktaDelegate = new OktaDelegate(oktaIssuer, oktaAudience, oktaWebClientId, oktaWebClientSecret, oktaScopes,
                oktaMcpClientId, symmetricSigningKey);
        String jiraEmail = System.getenv("JIRA_CLIENT_EMAIL");
        String jiraCloudId = System.getenv("JIRA_CLOUD_ID");
        String jiraToken = AwsServicesDelegate.fetchSmmParameterValue(System.getenv("JIRA_CLIENT_TOKEN_SSM_PARAMETER_KEY"));
        this.mcpHandler = new McpHandler(new JiraClient(jiraEmail, jiraToken, jiraCloudId));
        this.webHandler = new WebHandler();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> request, Context context) {
        if (DEBUG) {
            String userAgent = JsonUtils.getNestedField(request, "requestContext", "http", "userAgent");
            String sourceIp = JsonUtils.getNestedField(request, "requestContext", "http", "sourceIp");
            String sourceId = ((userAgent == null) ? "" : userAgent.substring(0, 6) + " ") + sourceIp;
            String path = JsonUtils.getNestedField(request, "requestContext", "http", "method") + ":" +
                    JsonUtils.getNestedField(request, "requestContext", "http", "path");
            Logger logger = new Logger(context, sourceId);
            logger.log("Request " + path, request);
            Map<String, Object> response = handle(request, context, logger);
            logger.log("Response " + path, response);
            return response;
        }
        return handle(request, context, new Logger(context));
    }

    public Map<String, Object> handle(Map<String, Object> request, Context context, Logger logger) {
        try {
            List<String> errors = validate(request);
            if (!errors.isEmpty()) {
                return handleInvalidRequest(request, errors, context);
            }
            String path = JsonUtils.getNestedField(request, "requestContext", "http", "path");
            if (oktaDelegate.isPublicPath(path)) {
                return oktaDelegate.handlePublicPath(path, request, logger);
            }
            // This server is stateless request/response: it offers no server-initiated
            // SSE stream (GET /mcp) or session teardown (DELETE /mcp). Per the MCP
            // Streamable HTTP spec, answer those with 405 so clients stop reopening the
            // stream every second instead of falling back to plain POST /mcp.
            if (MCP_PATH.equals(path)) {
                String httpMethod = JsonUtils.getNestedField(request, "requestContext", "http", "method");
                if (!"POST".equalsIgnoreCase(httpMethod)) {
                    return HttpUtils.response(405, Map.of("allow", "POST"), "");
                }
            }
            try {
                Jwt jwt = oktaDelegate.readJwt(request);
                return MCP_PATH.equals(path) ?
                        mcpHandler.handle(request, jwt) :
                        webHandler.handle(request, jwt, context);
            } catch (JwtVerificationException e) {
                return MCP_PATH.equals(path) ?
                        oktaDelegate.authenticationRedirectMcp(request) :
                        oktaDelegate.authenticationRedirectWeb(request);
            }
        } catch (Exception e) {
            logger.error("Unexpected exception: ", e);
            return handleUnexpectedException(request, e, context);
        }
    }

    private List<String> validate(Map<String, Object> request) {
        List<String> errors = new ArrayList<>();
        String path = JsonUtils.getNestedField(request, "requestContext", "http", "path");
        if (path == null) {
            errors.add("invalid path");
        }
        return errors;
    }

    public Map<String, Object> handleInvalidRequest(Map<String, Object> request, List<String> errors, Context context) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("awsRequestId", context.getAwsRequestId());
        if (DEBUG) {
            response.put("request", request);
        }
        response.put("errors", errors);
        return HttpUtils.responseJson(400, response);
    }

    public Map<String, Object> handleUnexpectedException(Map<String, Object> request, Exception e, Context context) {
        if (DEBUG) {
            Map<String, Object> exception = new LinkedHashMap<>();
            exception.put("type", e.getClass().getName());
            exception.put("message", e.getMessage());
            exception.put("stackTrace", CommonUtils.getStackTrace(e));
            Map<String, Object> exceptionDetails = new LinkedHashMap<>();
            exceptionDetails.put("awsRequestId", context.getAwsRequestId());
            exceptionDetails.put("request", request);
            exceptionDetails.put("exception", exception);
            return HttpUtils.responseJson(500, exceptionDetails);
        }
        return HttpUtils.responseJson(500, "Please try again later");
    }

}

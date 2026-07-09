package com.mgaray.oktaapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mgaray.oktaapp.common.AwsServicesDelegate;
import com.mgaray.oktaapp.common.JsonUtils;
import com.mgaray.oktaapp.jira.JiraDelegate;
import com.mgaray.oktaapp.mcp.McpHandler;
import com.mgaray.oktaapp.webapp.WepHandler;
import com.okta.jwt.Jwt;
import com.okta.jwt.JwtVerificationException;

import java.util.Map;

import static com.mgaray.oktaapp.OktaWebDelegate.CALLBACK_PATH;

public class OktaAppLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String MCP_PATH = "/mcp";
    private static final String REGISTER_PATH = "/register";
    private static final String WELL_KNOWN_PREFIX = "/.well-known/";

    private final OktaDelegate oktaDelegate;
    private final McpHandler mcpHandler;
    private final WepHandler webHandler;

    public OktaAppLambda() {
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
        this.webHandler = new WepHandler();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String path = JsonUtils.getNestedField(event, "requestContext", "http", "path");
        //public endpoints for authentication flow
        if (path != null && path.startsWith(WELL_KNOWN_PREFIX)) {
            return oktaDelegate.handleWellKnown(path, event);
        }
        if (REGISTER_PATH.equals(path)) {
            return oktaDelegate.handleRegister(event);
        }
        if (CALLBACK_PATH.equals(path)) {
            return oktaDelegate.handleCallback(event, context);
        }
        //private endpoints for mcp and web apps
        try {
            Jwt jwt = oktaDelegate.readJwt(event);
            return MCP_PATH.equals(path) ?
                    mcpHandler.handle(event, jwt) :
                    webHandler.handle(event, jwt, context);
        } catch (JwtVerificationException e) {
            return MCP_PATH.equals(path) ?
                    oktaDelegate.authenticationRedirectMcp(event) :
                    oktaDelegate.authenticationRedirectWeb(event, context);
        }
    }

}

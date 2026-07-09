package com.mgaray.oktaapp.mcp.jira;

/**
 * Raised when the Jira REST API returns a non-2xx response. Carries the HTTP
 * status and the human-readable message Jira supplied (its {@code errorMessages}
 * / {@code errors} fields), so the MCP layer can surface it as a tool error —
 * mirroring how the jira skill's {@code jira-fmt.py} prints "Jira error: ...".
 */
public class JiraException extends RuntimeException {

    private final int statusCode;

    public JiraException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

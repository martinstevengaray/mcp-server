package com.mgaray.oktaapp.mcp;

import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.mgaray.oktaapp.mcp.jira.JiraClient;
import com.mgaray.oktaapp.mcp.jira.JiraException;
import com.okta.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stateless, hand-rolled Model Context Protocol server over the Streamable
 * HTTP transport. Each Lambda invocation carries a single JSON-RPC request in
 * the body; we dispatch it and return a single JSON-RPC response. No SSE stream
 * and no session id are used (the tools are simple request/response calls).
 * Jira access is delegated to {@link JiraClient}.
 */
public class McpHandler {

    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    // Tool descriptors advertised by tools/list, with JSON Schema for arguments.
    private static final List<Map<String, Object>> TOOLS = List.of(
            tool("list_my_issues",
                    "List Jira issues assigned to you, most recently updated first.",
                    schema(Map.of("maxResults", intProp("Maximum number of issues to return (default 50).")),
                            List.of())),
            tool("search_issues",
                    "Search Jira issues with a JQL query.",
                    schema(Map.of(
                            "jql", stringProp("A JQL query, e.g. \"project = SDD AND status = 'To Do'\"."),
                            "maxResults", intProp("Maximum number of issues to return (default 50).")),
                            List.of("jql"))),
            tool("get_issue",
                    "Get a single Jira issue by key, including its description.",
                    schema(Map.of("key", stringProp("Issue key, e.g. SDD-1.")),
                            List.of("key"))),
            tool("create_issue",
                    "Create a new Jira issue.",
                    schema(Map.of(
                            "projectKey", stringProp("Project key the issue belongs to, e.g. SDD."),
                            "issueType", stringProp("Issue type name, e.g. Task, Bug, Story."),
                            "summary", stringProp("Short summary / title of the issue."),
                            "description", stringProp("Optional longer description (plain text).")),
                            List.of("projectKey", "issueType", "summary"))),
            tool("add_comment",
                    "Add a comment to a Jira issue.",
                    schema(Map.of(
                            "key", stringProp("Issue key, e.g. SDD-1."),
                            "body", stringProp("Comment text (plain text).")),
                            List.of("key", "body"))),
            tool("transition_issue",
                    "Move a Jira issue to a new status (e.g. In Progress, Done).",
                    schema(Map.of(
                            "key", stringProp("Issue key, e.g. SDD-1."),
                            "status", stringProp("Target status or transition name, e.g. \"In Progress\".")),
                            List.of("key", "status"))));

    private final JiraClient jira;

    public McpHandler(JiraClient jira) {
        this.jira = jira;
    }

    public Map<String, Object> handle(Map<String, Object> event, Jwt jwt) {
        Map<String, Object> request;
        try {
            request = JsonUtils.parse(readBody(event));
        } catch (Exception e) {
            return rpcError(null, -32700, "Parse error");
        }

        Object id = request.get("id");
        String method = request.get("method") instanceof String s ? s : null;
        if (method == null) {
            return rpcError(id, -32600, "Invalid Request: missing method");
        }
        // Notifications (e.g. notifications/initialized) expect no JSON-RPC reply.
        if (method.startsWith("notifications/")) {
            return HttpUtils.responseJson(202, "");
        }

        try {
            return switch (method) {
                case "initialize" -> rpcResult(id, initialize(request));
                case "ping" -> rpcResult(id, Map.of());
                case "tools/list" -> rpcResult(id, Map.of("tools", TOOLS));
                case "tools/call" -> rpcResult(id, callTool(request));
                default -> rpcError(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            return rpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> initialize(Map<String, Object> request) {
        String protocolVersion = JsonUtils.getNestedField(request, "params", "protocolVersion");
        return Map.of(
                "protocolVersion", protocolVersion != null ? protocolVersion : DEFAULT_PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "jira-mcp-server", "version", "1.0.0"));
    }

    private Map<String, Object> callTool(Map<String, Object> request) {
        String name = JsonUtils.getNestedField(request, "params", "name");
        Map<String, Object> args = JsonUtils.getNestedMap(request, "params", "arguments");
        try {
            String text = switch (name == null ? "" : name) {
                case "list_my_issues" -> jira.listMyIssues(intArg(args, "maxResults", 50));
                case "search_issues" -> jira.searchIssues(requiredArg(args, "jql"), intArg(args, "maxResults", 50));
                case "get_issue" -> jira.getIssue(requiredArg(args, "key"));
                case "create_issue" -> jira.createIssue(requiredArg(args, "projectKey"),
                        requiredArg(args, "issueType"), requiredArg(args, "summary"), optionalArg(args, "description"));
                case "add_comment" -> jira.addComment(requiredArg(args, "key"), requiredArg(args, "body"));
                case "transition_issue" -> jira.transitionIssue(requiredArg(args, "key"), requiredArg(args, "status"));
                default -> null;
            };
            if (text == null) {
                return toolError("Unknown tool: " + name);
            }
            return Map.of("content", List.of(textContent(text)));
        } catch (JiraException | IllegalArgumentException e) {
            // Tool-level failures are reported as an error result, not a protocol error.
            return toolError(e.getMessage());
        }
    }

    // ---- Argument helpers ----

    private static String requiredArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return s;
    }

    private static String optionalArg(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s ? s : null;
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    // ---- JSON-RPC envelope helpers ----

    private static Map<String, Object> toolError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(textContent("Error: " + message)));
        result.put("isError", true);
        return result;
    }

    private static Map<String, Object> textContent(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("result", result);
        return HttpUtils.responseJson(200, JsonUtils.toString(body));
    }

    private static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", error);
        return HttpUtils.responseJson(200, JsonUtils.toString(body));
    }

    private static String readBody(Map<String, Object> event) {
        String body = event.get("body") instanceof String s ? s : "";
        if (Boolean.TRUE.equals(event.get("isBase64Encoded"))) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return body;
    }

    // ---- Tool-descriptor builders ----

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }
}

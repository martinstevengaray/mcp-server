package com.mgaray.oktaapp.mcp.jira;

import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of the {@code claude-skills/jira} scripts: talks to the Atlassian
 * Cloud REST API v3 over Basic auth (email + API token) and formats responses
 * the way {@code jira-fmt.py} does. Constructed once per Lambda cold start so
 * the {@link HttpClient} and auth header are reused across warm invocations.
 */
public class JiraClient {

    // Field sets mirror the jira skill's list-my-tasks.sh / get-issue.sh scripts.
    private static final String SEARCH_FIELDS = "summary,status,priority,issuetype,project,description";
    private static final String ISSUE_FIELDS = "summary,status,assignee,description";
    private static final String MY_ISSUES_JQL = "assignee = currentUser() ORDER BY updated DESC";

    // Block-level ADF nodes after which jira-fmt.py inserts a line break.
    private static final Set<String> BLOCK_TYPES =
            Set.of("paragraph", "heading", "listItem", "blockquote", "codeBlock");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String authHeader;

    public JiraClient(String email, String apiToken, String cloudId) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUrl = "https://api.atlassian.com/ex/jira/" + cloudId + "/rest/api/3";
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    // ---- Tool operations (return formatted text ready for an MCP text result) ----

    /** Issues assigned to the token's user, most recently updated first. */
    public String listMyIssues(int maxResults) {
        return searchIssues(MY_ISSUES_JQL, maxResults);
    }

    /** Arbitrary JQL search, formatted as one compact row per issue. */
    public String searchIssues(String jql, int maxResults) {
        String url = baseUrl + "/search/jql"
                + "?jql=" + HttpUtils.urlEncode(jql)
                + "&fields=" + HttpUtils.urlEncode(SEARCH_FIELDS)
                + "&maxResults=" + maxResults;
        return formatIssues(getJson(url));
    }

    /** A single issue by key, with its description flattened from ADF to text. */
    public String getIssue(String key) {
        String url = baseUrl + "/issue/" + HttpUtils.urlEncode(key)
                + "?fields=" + HttpUtils.urlEncode(ISSUE_FIELDS);
        return formatIssue(getJson(url));
    }

    public String createIssue(String projectKey, String issueType, String summary, String description) {
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("project", Map.of("key", projectKey));
        fields.put("issuetype", Map.of("name", issueType));
        fields.put("summary", summary);
        if (description != null && !description.isBlank()) {
            fields.put("description", textToAdf(description));
        }
        Map<String, Object> created = postJson(baseUrl + "/issue", Map.of("fields", fields));
        return "Created issue " + created.getOrDefault("key", "?");
    }

    public String addComment(String key, String body) {
        postJson(baseUrl + "/issue/" + HttpUtils.urlEncode(key) + "/comment",
                Map.of("body", textToAdf(body)));
        return "Added comment to " + key;
    }

    /** Resolve a target status/transition name to its id, then apply it. */
    public String transitionIssue(String key, String status) {
        Map<String, Object> data = getJson(baseUrl + "/issue/" + HttpUtils.urlEncode(key) + "/transitions");
        List<Map<String, Object>> transitions = asList(data.get("transitions"));
        String matchId = null;
        List<String> available = new ArrayList<>();
        for (Map<String, Object> t : transitions) {
            String name = str(t.get("name"));
            String toName = JsonUtils.getNestedField(t, "to", "name");
            available.add(name);
            if (status.equalsIgnoreCase(name) || status.equalsIgnoreCase(toName)) {
                matchId = str(t.get("id"));
                break;
            }
        }
        if (matchId == null) {
            throw new JiraException(400, "No transition matching '" + status
                    + "' on " + key + ". Available: " + String.join(", ", available));
        }
        postJson(baseUrl + "/issue/" + HttpUtils.urlEncode(key) + "/transitions",
                Map.of("transition", Map.of("id", matchId)));
        return "Transitioned " + key + " -> " + status;
    }

    // ---- HTTP plumbing ----

    private Map<String, Object> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("authorization", authHeader)
                .header("accept", "application/json")
                .GET()
                .build();
        return handle(send(request));
    }

    private Map<String, Object> postJson(String url, Object body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("authorization", authHeader)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toString(body), StandardCharsets.UTF_8))
                .build();
        return handle(send(request));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new JiraException(0, "Could not reach Jira: " + e.getMessage());
        }
    }

    private Map<String, Object> handle(HttpResponse<String> response) {
        String body = response.body();
        Map<String, Object> parsed = (body == null || body.isBlank()) ? Map.of() : JsonUtils.parse(body);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return parsed;
        }
        throw new JiraException(response.statusCode(), extractError(parsed, response.statusCode()));
    }

    private String extractError(Map<String, Object> body, int statusCode) {
        List<String> messages = new ArrayList<>();
        if (body.get("errorMessages") instanceof List<?> list) {
            for (Object m : list) {
                messages.add(String.valueOf(m));
            }
        }
        if (body.get("errors") instanceof Map<?, ?> errors && !errors.isEmpty()) {
            errors.forEach((k, v) -> messages.add(k + ": " + v));
        }
        return messages.isEmpty() ? ("Jira returned HTTP " + statusCode) : String.join("; ", messages);
    }

    // ---- Formatting (ports of jira-fmt.py) ----

    private String formatIssues(Map<String, Object> data) {
        List<Map<String, Object>> issues = asList(data.get("issues"));
        if (issues.isEmpty()) {
            return "No issues.";
        }
        List<String> rows = new ArrayList<>();
        for (Map<String, Object> issue : issues) {
            Map<String, Object> f = JsonUtils.getNestedMap(issue, "fields");
            rows.add(String.join("\t",
                    str(issue.getOrDefault("key", "?")),
                    orDefault(JsonUtils.getNestedField(f, "status", "name"), "?"),
                    orDefault(JsonUtils.getNestedField(f, "priority", "name"), "-"),
                    orDefault(JsonUtils.getNestedField(f, "summary"), "")));
        }
        return String.join("\n", rows);
    }

    private String formatIssue(Map<String, Object> data) {
        Map<String, Object> f = JsonUtils.getNestedMap(data, "fields");
        String assignee = orDefault(JsonUtils.getNestedField(f, "assignee", "displayName"), "Unassigned");
        String desc = adfToText(f.get("description")).strip();
        return String.join("\n",
                orDefault(JsonUtils.getNestedField(data, "key"), "?") + "  "
                        + orDefault(JsonUtils.getNestedField(f, "summary"), ""),
                "Status:   " + orDefault(JsonUtils.getNestedField(f, "status", "name"), "?"),
                "Assignee: " + assignee,
                "",
                desc.isEmpty() ? "(no description)" : desc);
    }

    /** Recursively pull plain text out of an Atlassian Document Format tree. */
    private String adfToText(Object node) {
        if (node instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object child : list) {
                sb.append(adfToText(child));
            }
            return sb.toString();
        }
        if (node instanceof Map<?, ?> map) {
            String text = map.get("text") instanceof String s ? s : "";
            String children = adfToText(map.get("content"));
            String sep = BLOCK_TYPES.contains(map.get("type")) ? "\n" : "";
            return text + children + sep;
        }
        return "";
    }

    /** Wrap plain text in a minimal ADF document (required by v3 write bodies). */
    private Map<String, Object> textToAdf(String text) {
        return Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                        "type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text)))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

package com.mgaray.oktaapp.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpUtils {

    private static final Map<String, String> JSON_HEADERS = Map.of("content-type", "application/json");

    public static Map<String, Object> htmlError(int statusCode, String message) {
        return response(statusCode, Map.of("content-type", "text/html; charset=utf-8"),
                "<!DOCTYPE html><html><body><h1>Error</h1><p>"
                        + message + "</p><p><a href=\"/\">Try again</a></p></body></html>");
    }

    public static Map<String, Object> response(int statusCode, Map<String, String> headers, String body) {
        return response(statusCode, headers, body, null);
    }

    public static Map<String, Object> responseJson(int statusCode, String body) {
        return response(statusCode, JSON_HEADERS, body, null);
    }

    public static Map<String, Object> responseJson(int statusCode, Map<String, Object> body) {
        return response(statusCode, JSON_HEADERS, JsonUtils.toString(body), null);
    }

    public static Map<String, Object> responseJson(int statusCode, Map<String, String> otherHeaders, String body) {
        Map<String, String> headers = new HashMap<>(otherHeaders);
        headers.putAll(JSON_HEADERS);
        return response(statusCode, headers, body, null);
    }

    public static Map<String, Object> response(int statusCode, Map<String, String> headers,
                                                String body, List<String> cookies) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("headers", headers);
        response.put("body", body);
        if (cookies != null) {
            response.put("cookies", cookies);
        }
        return response;
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static Map<String,Object> parseBase64EncodedBody(Map<String, Object> request) {
        String body = request.get("body") instanceof String s ? s : "";
        if (Boolean.TRUE.equals(request.get("isBase64Encoded"))) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        return JsonUtils.parse(body);
    }

    public static String readCookieValue(Map<String, Object> request, String cookieName) {
        if (request.get("cookies") instanceof List<?> cookies) {
            for (Object cookie : cookies) {
                if (cookie instanceof String s && s.startsWith(cookieName + "=")) {
                    return s.substring(cookieName.length() + 1);
                }
            }
        }
        return null;
    }

    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}

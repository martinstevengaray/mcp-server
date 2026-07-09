package com.mgaray.oktaapp.webapp;

import com.amazonaws.services.lambda.runtime.Context;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.Map;

public class WepHandler {

    public WepHandler() {
    }

    public Map<String, Object> handle(Map<String, Object> event, Jwt jwt, Context context) {
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
        return HttpUtils.responseJson(200, JsonUtils.toString(response));
    }

}

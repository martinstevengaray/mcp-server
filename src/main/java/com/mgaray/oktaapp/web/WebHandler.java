package com.mgaray.oktaapp.web;

import com.amazonaws.services.lambda.runtime.Context;
import com.mgaray.oktaapp.McpServerLambda;
import com.mgaray.oktaapp.common.HttpUtils;
import com.mgaray.oktaapp.common.JsonUtils;
import com.okta.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.Map;

public class WebHandler {

    public WebHandler() {
    }

    public Map<String, Object> handle(Map<String, Object> request, Jwt jwt, Context context) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("awsRequestId", context.getAwsRequestId());
        if (McpServerLambda.DEBUG) {
            response.put("request", request);
        }
        response.put("jwtClaims", jwt.getClaims());
        return HttpUtils.responseJson(200, JsonUtils.toString(response));
    }

}

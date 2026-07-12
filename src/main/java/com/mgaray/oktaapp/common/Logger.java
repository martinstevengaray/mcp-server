package com.mgaray.oktaapp.common;

import  com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

import java.util.Map;

public class Logger {

    private final LambdaLogger lambdaLogger;
    private final String idPrefix;

    public Logger(Context context) {
        this(context, null);
    }

    public Logger(Context context, String id) {
        this.lambdaLogger = context.getLogger();
        this.idPrefix = (id == null) ? "" : id + ": ";
    }

    public void log(String message) {
        this.lambdaLogger.log(idPrefix + message);
    }

    public void log(String message, Map<String ,Object> objectMap) {
        this.lambdaLogger.log(idPrefix + message + " : " + JsonUtils.toString(objectMap));
    }

    public void error(String message, Exception e) {
        String stackTrace = CommonUtils.getStackTrace(e);
        this.lambdaLogger.log(idPrefix + "ERROR: " +message + " : " + stackTrace);
    }

}


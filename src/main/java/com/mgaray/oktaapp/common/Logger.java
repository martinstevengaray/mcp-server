package com.mgaray.oktaapp.common;

import  com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

public class Logger {

    private final LambdaLogger lambdaLogger;
    private final String id;

    public Logger(Context context) {
        this.lambdaLogger = context.getLogger();
        this.id = context.getAwsRequestId();
    }

    public void log(String message) {
        this.lambdaLogger.log(id + ": " + message);
    }

    public void error(String message, Exception e) {
        String stackTrace = CommonUtils.getStackTrace(e);
        this.lambdaLogger.log(id + ": " +message + " : " + stackTrace);
    }

}


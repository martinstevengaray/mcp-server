package com.mgaray.oktaapp.common;

import java.io.PrintWriter;
import java.io.StringWriter;

public class CommonUtils {

    public static String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

}

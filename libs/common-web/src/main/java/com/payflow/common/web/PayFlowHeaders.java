package com.payflow.common.web;

/**
 * Identity headers the edge gateway stamps after authenticating a Bearer token.
 * Downstream services must trust these only from the gateway, never from clients.
 */
public final class PayFlowHeaders {

    public static final String MERCHANT_ID = "X-PayFlow-Merchant-Id";
    public static final String KEY_ID = "X-PayFlow-Key-Id";
    public static final String ENVIRONMENT = "X-PayFlow-Environment";

    private PayFlowHeaders() {
    }

    public static boolean isIdentityHeader(String name) {
        return name != null && name.regionMatches(true, 0, "X-PayFlow-", 0, "X-PayFlow-".length());
    }
}

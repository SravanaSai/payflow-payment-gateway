package com.payflow.common.security;

/**
 * Environment an API key belongs to. Encoded directly into the key string
 * ({@code sk_live_...} vs {@code sk_test_...}) so a misconfigured client is
 * visible at a glance in code, logs and support tickets.
 *
 * <p>Test keys must never be accepted by money-moving code paths.
 */
public enum ApiKeyEnvironment {

    LIVE("live"),
    TEST("test");

    private final String token;

    ApiKeyEnvironment(String token) {
        this.token = token;
    }

    /** The lowercase token embedded in the key string. */
    public String token() {
        return token;
    }
}

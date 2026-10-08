package com.payflow.gateway.ratelimit;

public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";

    private RateLimitHeaders() {
    }
}

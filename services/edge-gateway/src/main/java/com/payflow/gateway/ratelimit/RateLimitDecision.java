package com.payflow.gateway.ratelimit;

/** Outcome of a single token-bucket check. */
public record RateLimitDecision(
        boolean allowed,
        int limit,
        long remaining,
        long retryAfterSeconds,
        boolean degraded) {

    public static RateLimitDecision allowed(int limit, long remaining) {
        return new RateLimitDecision(true, limit, remaining, 0, false);
    }

    public static RateLimitDecision denied(int limit, long remaining, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, remaining, retryAfterSeconds, false);
    }

    public static RateLimitDecision degradedAllowed(int limit) {
        return new RateLimitDecision(true, limit, limit, 0, true);
    }
}

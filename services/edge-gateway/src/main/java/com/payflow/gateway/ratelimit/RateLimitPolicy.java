package com.payflow.gateway.ratelimit;

/** Token-bucket parameters for one rate-limit tier. */
public record RateLimitPolicy(String name, int capacity, double refillPerSecond) {

    public int ttlSeconds() {
        if (refillPerSecond <= 0) {
            return capacity + 1;
        }
        return (int) Math.ceil(capacity / refillPerSecond) + 1;
    }
}

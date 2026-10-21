package com.payflow.payment.adapter.out.processor.http;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;

/** Which failures retry and which ones trip the circuit breaker — single source of truth. */
public final class PspResilience {

    public static final String INSTANCE = "psp";

    private PspResilience() {
    }

    public static RetryConfig.Builder<Object> classifyRetry(RetryConfig.Builder<Object> builder) {
        return builder.retryExceptions(PspTransientException.class);
    }

    public static CircuitBreakerConfig.Builder classifyBreaker(CircuitBreakerConfig.Builder builder) {
        return builder.recordExceptions(PspTransientException.class)
                .ignoreExceptions(PspRejectedException.class);
    }
}

package com.payflow.common.web;

/** Shared HTTP header and MDC keys for request tracing. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }
}

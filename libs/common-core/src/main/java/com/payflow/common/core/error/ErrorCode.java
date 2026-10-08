package com.payflow.common.core.error;

/**
 * Stable, machine-readable error codes returned in API responses.
 * Merchants integrate programmatically — they need codes, not just HTTP status.
 */
public enum ErrorCode {
    PAYMENT_NOT_FOUND("Payment does not exist"),
    INVALID_STATE_TRANSITION("Payment cannot transition to the requested state"),
    IDEMPOTENCY_CONFLICT("Idempotency key reused for a different request"),
    IDEMPOTENCY_IN_PROGRESS("A request with this idempotency key is still being processed"),
    CONCURRENT_MODIFICATION("The resource was modified by a concurrent request"),
    CURRENCY_MISMATCH("Operation requires matching currencies"),
    VALIDATION_FAILED("Request validation failed"),
    MERCHANT_NOT_FOUND("Merchant does not exist"),
    API_KEY_NOT_FOUND("API key does not exist or is not usable"),
    UNAUTHORIZED("Authentication is required"),
    FORBIDDEN("You are not allowed to access this resource"),
    RATE_LIMITED("Too many requests"),
    SERVICE_UNAVAILABLE("A required upstream service is unavailable"),
    INTERNAL_ERROR("An unexpected error occurred");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}

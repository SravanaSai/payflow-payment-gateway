package com.payflow.mockpsp.web;

import org.springframework.http.HttpStatus;

/** An error mock-psp reports in its own {@code {"error": {...}}} format. */
public class PspException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String code;

    private PspException(HttpStatus status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    public static PspException invalid(String code, String message) {
        return new PspException(HttpStatus.BAD_REQUEST, "invalid_request_error", code, message);
    }

    public static PspException notFound(String message) {
        return new PspException(HttpStatus.NOT_FOUND, "invalid_request_error", "resource_missing", message);
    }

    public static PspException unavailable() {
        return new PspException(HttpStatus.SERVICE_UNAVAILABLE, "api_error", "service_unavailable",
                "The processor is temporarily unavailable.");
    }

    public static PspException idempotencyKeyReused() {
        return new PspException(HttpStatus.BAD_REQUEST, "idempotency_error", "idempotency_key_reused",
                "This idempotency key was already used with a different request.");
    }

    public static PspException idempotencyKeyInUse() {
        return new PspException(HttpStatus.CONFLICT, "idempotency_error", "idempotency_key_in_use",
                "A request with this idempotency key is still being processed.");
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String code() {
        return code;
    }
}

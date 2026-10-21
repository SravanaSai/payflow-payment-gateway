package com.payflow.payment.adapter.out.processor.http;

/** The processor answered, but we cannot use the answer. Retrying will not help. */
final class PspRejectedException extends RuntimeException {

    PspRejectedException(String message) {
        super(message);
    }

    PspRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}

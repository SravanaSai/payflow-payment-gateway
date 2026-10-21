package com.payflow.payment.adapter.out.processor.http;

/** A failure that may succeed on retry: timeout, network error, 5xx, 429, key in use. */
final class PspTransientException extends RuntimeException {

    PspTransientException(String message) {
        super(message);
    }

    PspTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}

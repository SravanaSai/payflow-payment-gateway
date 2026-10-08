package com.payflow.common.core.error;

/**
 * Base exception for PayFlow domain and application errors.
 * Carries an {@link ErrorCode} so the web layer (Step 4) can map it to RFC 7807 Problem Details.
 */
public class PayFlowException extends RuntimeException {

    private final ErrorCode errorCode;

    public PayFlowException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public PayFlowException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PayFlowException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

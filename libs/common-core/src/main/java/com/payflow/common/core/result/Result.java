package com.payflow.common.core.result;

import com.payflow.common.core.error.ErrorCode;

import java.util.Objects;
import java.util.function.Function;

/**
 * Represents either a successful value or a failure with an error code.
 * Use for expected business outcomes (e.g. currency mismatch) instead of throwing.
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    static <T> Result<T> success(T value) {
        return new Success<>(Objects.requireNonNull(value, "value must not be null"));
    }

    static <T> Result<T> failure(ErrorCode errorCode) {
        return new Failure<>(Objects.requireNonNull(errorCode, "errorCode must not be null"));
    }

    static <T> Result<T> failure(ErrorCode errorCode, String message) {
        return new Failure<>(Objects.requireNonNull(errorCode, "errorCode must not be null"), message);
    }

    boolean isSuccess();

    boolean isFailure();

    T getValue();

    ErrorCode getErrorCode();

    default <U> Result<U> map(Function<T, U> mapper) {
        if (isSuccess()) {
            return success(mapper.apply(getValue()));
        }
        return failure(getErrorCode());
    }

    record Success<T>(T value) implements Result<T> {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public T getValue() {
            return value;
        }

        @Override
        public ErrorCode getErrorCode() {
            throw new IllegalStateException("Cannot get error code from a successful result");
        }
    }

    record Failure<T>(ErrorCode errorCode, String message) implements Result<T> {
        Failure(ErrorCode errorCode) {
            this(errorCode, errorCode.getDefaultMessage());
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T getValue() {
            throw new IllegalStateException("Cannot get value from a failed result: " + errorCode);
        }

        @Override
        public ErrorCode getErrorCode() {
            return errorCode;
        }

        public String getMessage() {
            return message;
        }
    }
}

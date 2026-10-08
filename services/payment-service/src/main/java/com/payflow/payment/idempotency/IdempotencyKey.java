package com.payflow.payment.idempotency;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Objects;

/** A client's idempotency key, scoped to the owner that sent it: the same string from another owner is another key. */
record IdempotencyKey(PaymentOwner owner, String value) {

    static final int MAX_LENGTH = 255;

    IdempotencyKey {
        Objects.requireNonNull(owner, "owner must not be null");
        if (value == null || value.isBlank()) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED,
                    IdempotencyFilter.KEY_HEADER + " header is required");
        }
        if (value.length() > MAX_LENGTH || !value.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED,
                    IdempotencyFilter.KEY_HEADER + " must be 1-" + MAX_LENGTH + " visible ASCII characters");
        }
    }
}

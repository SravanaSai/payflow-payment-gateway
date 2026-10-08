package com.payflow.payment.application.port.in;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Objects;

/** Inbound port — capture all or part of an authorized payment. */
public interface CapturePaymentUseCase {

    Payment capture(CapturePaymentCommand command);

    /** A {@code null} amount captures everything that was authorized. */
    record CapturePaymentCommand(PaymentId id, PaymentOwner owner, Long amountMinor) {

        public CapturePaymentCommand {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            if (amountMinor != null && amountMinor <= 0) {
                throw new PayFlowException(ErrorCode.VALIDATION_FAILED, "Capture amount must be positive");
            }
        }
    }
}

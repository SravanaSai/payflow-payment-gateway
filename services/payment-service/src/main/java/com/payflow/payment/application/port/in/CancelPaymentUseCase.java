package com.payflow.payment.application.port.in;

import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Objects;

/** Inbound port — cancel a pending payment or void an authorization. */
public interface CancelPaymentUseCase {

    Payment cancel(CancelPaymentCommand command);

    record CancelPaymentCommand(PaymentId id, PaymentOwner owner, String reason) {

        public CancelPaymentCommand {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
        }
    }
}

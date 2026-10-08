package com.payflow.payment.application.port.in;

import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Objects;

/** Inbound port — move a pending payment to AUTHORIZED. */
public interface AuthorizePaymentUseCase {

    Payment authorize(AuthorizePaymentCommand command);

    record AuthorizePaymentCommand(PaymentId id, PaymentOwner owner) {

        public AuthorizePaymentCommand {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
        }
    }
}

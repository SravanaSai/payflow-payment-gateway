package com.payflow.payment.application.port.in;

import com.payflow.common.core.domain.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Objects;

/** Inbound port — start a new payment. */
public interface CreatePaymentUseCase {

    Payment create(CreatePaymentCommand command);

    record CreatePaymentCommand(PaymentOwner owner, Money amount, String description, String reference) {

        public CreatePaymentCommand {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }
}

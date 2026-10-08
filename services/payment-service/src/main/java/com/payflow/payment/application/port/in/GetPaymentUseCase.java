package com.payflow.payment.application.port.in;

import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

/** Inbound port — read one payment, visible only to its owner. */
public interface GetPaymentUseCase {

    Payment get(PaymentId id, PaymentOwner owner);
}

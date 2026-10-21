package com.payflow.payment.application.port.out;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;

public record CaptureRequest(
        PaymentId paymentId,
        String processorReference,
        Money amount,
        String outboundIdempotencyKey) {

    public CaptureRequest {
        if (outboundIdempotencyKey == null || outboundIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("outboundIdempotencyKey must not be blank");
        }
    }

    public static CaptureRequest from(Payment payment, Money captureAmount) {
        int attempt = payment.getCaptureAttempts();
        String suffix = attempt == 0 ? "" : ":" + attempt;
        return new CaptureRequest(
                payment.getId(),
                payment.getProcessorReference(),
                captureAmount,
                payment.getId().value() + ":capture" + suffix);
    }
}

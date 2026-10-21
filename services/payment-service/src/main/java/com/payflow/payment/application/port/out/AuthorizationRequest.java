package com.payflow.payment.application.port.out;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;

/**
 * Outbound authorize call. The idempotency key lets the processor treat retries
 * as the same hold after a timeout (Phase 3 Step 2).
 */
public record AuthorizationRequest(PaymentId paymentId, Money amount, String outboundIdempotencyKey) {

    public AuthorizationRequest {
        if (outboundIdempotencyKey == null || outboundIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("outboundIdempotencyKey must not be blank");
        }
    }

    public static AuthorizationRequest from(Payment payment) {
        return new AuthorizationRequest(
                payment.getId(),
                payment.getAmount(),
                payment.getId().value() + ":authorize");
    }
}

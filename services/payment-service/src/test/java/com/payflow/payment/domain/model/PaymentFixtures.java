package com.payflow.payment.domain.model;

import com.payflow.common.core.domain.Currency;
import com.payflow.common.core.domain.Money;
import com.payflow.common.core.ids.MerchantId;

import java.time.Instant;

/** Shared test data. Payments reach a status only through legal commands, never by rehydrating into it. */
public final class PaymentFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    public static final Instant LATER = CREATED_AT.plusSeconds(60);
    public static final long AMOUNT = 10_000;
    public static final long PARTIAL_CAPTURE = 6_000;

    private PaymentFixtures() {
    }

    public static PaymentOwner newOwner() {
        return new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST);
    }

    public static Money inr(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, Currency.INR);
    }

    public static Payment inStatus(PaymentStatus status, PaymentOwner owner) {
        Payment payment = Payment.create(owner, inr(AMOUNT), "Order 42", "order-42", CREATED_AT);
        switch (status) {
            case PENDING -> {
            }
            case AUTHORIZED -> payment.authorize("fixture-psp", CREATED_AT);
            case CAPTURED -> {
                payment.authorize("fixture-psp", CREATED_AT);
                payment.capture(inr(PARTIAL_CAPTURE), CREATED_AT);
            }
            case FAILED -> payment.fail("card declined", CREATED_AT);
            case CANCELLED -> payment.cancel("customer request", CREATED_AT);
            case REFUNDED -> {
                payment.authorize("fixture-psp", CREATED_AT);
                payment.capture(inr(PARTIAL_CAPTURE), CREATED_AT);
                payment.refund(CREATED_AT);
            }
        }
        return payment;
    }

    public static Payment inStatus(PaymentStatus status) {
        return inStatus(status, newOwner());
    }
}

package com.payflow.payment.adapter.in.web.dto;

import com.payflow.common.core.domain.Currency;
import com.payflow.payment.domain.model.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Amounts are in minor units: 1050 means ₹10.50. */
public record CreatePaymentRequest(
        @NotNull @Positive Long amountMinor,
        @NotNull Currency currency,
        @Size(max = Payment.MAX_DESCRIPTION_LENGTH) String description,
        @Size(max = Payment.MAX_REFERENCE_LENGTH) String reference) {
}

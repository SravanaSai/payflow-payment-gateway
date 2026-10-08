package com.payflow.payment.adapter.in.web.dto;

import com.payflow.common.core.domain.Currency;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

/** API response DTO — decouples the HTTP contract from the domain model. */
public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        long amountMinor,
        Currency currency,
        long capturedAmountMinor,
        PaymentEnvironment environment,
        String description,
        String reference,
        String failureReason,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId().value(),
                payment.getStatus(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currency(),
                payment.getCapturedAmount().minorUnits(),
                payment.getOwner().environment(),
                payment.getDescription(),
                payment.getReference(),
                payment.getFailureReason(),
                payment.getCancellationReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}

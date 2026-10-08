package com.payflow.payment.adapter.out.persistence;

import com.payflow.common.core.domain.Currency;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentEnvironment environment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(length = 500)
    private String description;

    @Column(length = 128)
    private String reference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    // Boxed on purpose: a null version tells Spring Data the row is new, so save()
    // issues a plain INSERT instead of a merge that first SELECTs a row that isn't there.
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentJpaEntity() {
    }

    PaymentJpaEntity(
            UUID id,
            UUID merchantId,
            PaymentEnvironment environment,
            PaymentStatus status,
            long amountMinor,
            Currency currency,
            long capturedAmountMinor,
            String description,
            String reference,
            String failureReason,
            String cancellationReason,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.environment = environment;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.capturedAmountMinor = capturedAmountMinor;
        this.description = description;
        this.reference = reference;
        this.failureReason = failureReason;
        this.cancellationReason = cancellationReason;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getMerchantId() {
        return merchantId;
    }

    PaymentEnvironment getEnvironment() {
        return environment;
    }

    PaymentStatus getStatus() {
        return status;
    }

    long getAmountMinor() {
        return amountMinor;
    }

    Currency getCurrency() {
        return currency;
    }

    long getCapturedAmountMinor() {
        return capturedAmountMinor;
    }

    String getDescription() {
        return description;
    }

    String getReference() {
        return reference;
    }

    String getFailureReason() {
        return failureReason;
    }

    String getCancellationReason() {
        return cancellationReason;
    }

    Long getVersion() {
        return version;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}

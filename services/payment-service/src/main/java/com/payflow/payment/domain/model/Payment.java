package com.payflow.payment.domain.model;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;

import java.time.Instant;
import java.util.Objects;

/**
 * Payment aggregate root — pure domain object with no JPA annotations.
 *
 * <p>Every state change funnels through {@link #transitionTo}, so the rules in
 * {@link PaymentStatus} cannot be bypassed. Each command checks its state guard,
 * then validates its arguments, then mutates; a rejected command leaves the
 * payment exactly as it was.
 */
public final class Payment {

    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_REFERENCE_LENGTH = 128;
    public static final int MAX_REASON_LENGTH = 255;
    public static final int MAX_PROCESSOR_REFERENCE_LENGTH = 128;

    private final PaymentId id;
    private final PaymentOwner owner;
    private final Money amount;
    private final String description;
    private final String reference;
    private final Long version;
    private final Instant createdAt;
    private Money capturedAmount;
    private PaymentStatus status;
    private String failureReason;
    private String cancellationReason;
    private String processorReference;
    private int captureAttempts;
    private Instant updatedAt;

    private Payment(
            PaymentId id,
            PaymentOwner owner,
            Money amount,
            Money capturedAmount,
            PaymentStatus status,
            String description,
            String reference,
            String failureReason,
            String cancellationReason,
            String processorReference,
            int captureAttempts,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.capturedAmount = Objects.requireNonNull(capturedAmount, "capturedAmount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.description = description;
        this.reference = reference;
        this.failureReason = failureReason;
        this.cancellationReason = cancellationReason;
        this.processorReference = processorReference;
        this.captureAttempts = captureAttempts;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (capturedAmount.currency() != amount.currency()) {
            throw new IllegalArgumentException("capturedAmount currency must match amount currency");
        }
        if (capturedAmount.isGreaterThan(amount)) {
            throw new IllegalArgumentException("capturedAmount must not exceed amount");
        }
    }

    /** Starts a new payment from untrusted input, validating every argument. */
    public static Payment create(
            PaymentOwner owner,
            Money amount,
            String description,
            String reference,
            Instant now) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.isZero()) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED, "Payment amount must be positive");
        }
        return new Payment(
                PaymentId.generate(),
                owner,
                amount,
                Money.ofMinorUnits(0, amount.currency()),
                PaymentStatus.PENDING,
                optionalText(description, "description", MAX_DESCRIPTION_LENGTH),
                optionalText(reference, "reference", MAX_REFERENCE_LENGTH),
                null,
                null,
                null,
                0,
                null,
                now,
                now);
    }

    /**
     * Reconstructs a stored payment. Input rules are not re-checked here: the
     * database constraints mirror them, so stored state is already valid.
     */
    public static Payment rehydrate(
            PaymentId id,
            PaymentOwner owner,
            Money amount,
            Money capturedAmount,
            PaymentStatus status,
            String description,
            String reference,
            String failureReason,
            String cancellationReason,
            String processorReference,
            int captureAttempts,
            Long version,
            Instant createdAt,
            Instant updatedAt) {
        return new Payment(id, owner, amount, capturedAmount, status, description, reference,
                failureReason, cancellationReason, processorReference, captureAttempts, version, createdAt, updatedAt);
    }

    /** Validates without mutating; used before an outbound processor call. */
    public void checkAuthorizable() {
        ensureCanTransitionTo(PaymentStatus.AUTHORIZED);
    }

    /** Validates capture amount and state without mutating; used before an outbound processor call. */
    public void checkCapturable(Money requested) {
        ensureCanTransitionTo(PaymentStatus.CAPTURED);
        requireCapturable(requested);
    }

    public void authorize(String processorReference, Instant now) {
        ensureCanTransitionTo(PaymentStatus.AUTHORIZED);
        String normalized = requiredText(processorReference, "processor reference", MAX_PROCESSOR_REFERENCE_LENGTH);
        transitionTo(PaymentStatus.AUTHORIZED, now);
        this.processorReference = normalized;
    }

    public void capture(Instant now) {
        capture(amount, now);
    }

    public void capture(Money requested, Instant now) {
        ensureCanTransitionTo(PaymentStatus.CAPTURED);
        requireCapturable(requested);
        transitionTo(PaymentStatus.CAPTURED, now);
        this.capturedAmount = requested;
    }

    /**
     * Records a definite processor decline for a capture attempt without leaving AUTHORIZED.
     * The next capture uses a new outbound idempotency key ({@code :capture:N}).
     */
    public void recordCaptureDecline(Instant now) {
        ensureCanTransitionTo(PaymentStatus.CAPTURED);
        captureAttempts++;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(String reason, Instant now) {
        ensureCanTransitionTo(PaymentStatus.CANCELLED);
        String normalizedReason = optionalText(reason, "cancellation reason", MAX_REASON_LENGTH);
        transitionTo(PaymentStatus.CANCELLED, now);
        this.cancellationReason = normalizedReason;
    }

    public void fail(String reason, Instant now) {
        ensureCanTransitionTo(PaymentStatus.FAILED);
        String normalizedReason = requiredText(reason, "failure reason", MAX_REASON_LENGTH);
        transitionTo(PaymentStatus.FAILED, now);
        this.failureReason = normalizedReason;
    }

    /** Full refunds only; partial refunds arrive with the Refund aggregate in Phase 5. */
    public void refund(Instant now) {
        transitionTo(PaymentStatus.REFUNDED, now);
    }

    public PaymentId getId() {
        return id;
    }

    public PaymentOwner getOwner() {
        return owner;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getCapturedAmount() {
        return capturedAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public String getReference() {
        return reference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public String getProcessorReference() {
        return processorReference;
    }

    public int getCaptureAttempts() {
        return captureAttempts;
    }

    /** Concurrency token; {@code null} until the payment is first persisted. */
    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void transitionTo(PaymentStatus target, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        ensureCanTransitionTo(target);
        this.status = target;
        this.updatedAt = now;
    }

    private void ensureCanTransitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new PayFlowException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move payment from " + status + " to " + target);
        }
    }

    private void requireCapturable(Money requested) {
        Objects.requireNonNull(requested, "capture amount must not be null");
        if (requested.currency() != amount.currency()) {
            throw new PayFlowException(ErrorCode.CURRENCY_MISMATCH,
                    "Capture currency " + requested.currency()
                            + " does not match payment currency " + amount.currency());
        }
        if (requested.isZero()) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED, "Capture amount must be positive");
        }
        if (requested.isGreaterThan(amount)) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED,
                    "Capture amount " + requested.minorUnits()
                            + " exceeds authorized amount " + amount.minorUnits());
        }
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED,
                    field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String text = optionalText(value, field, maxLength);
        if (text == null) {
            throw new PayFlowException(ErrorCode.VALIDATION_FAILED, field + " must not be blank");
        }
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Payment payment && id.equals(payment.id));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Payment[id=" + id + ", status=" + status + "]";
    }
}

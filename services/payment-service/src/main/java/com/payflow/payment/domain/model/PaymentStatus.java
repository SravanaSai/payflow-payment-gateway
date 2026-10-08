package com.payflow.payment.domain.model;

import java.util.Objects;

/**
 * Payment lifecycle states. The legal transitions live here and nowhere else,
 * so every operation on {@link Payment} enforces the same rules.
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    REFUNDED;

    // No default branch: a new state must be placed in the table before this compiles.
    public boolean canTransitionTo(PaymentStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        return switch (this) {
            case PENDING -> target == AUTHORIZED || target == FAILED || target == CANCELLED;
            case AUTHORIZED -> target == CAPTURED || target == CANCELLED;
            case CAPTURED -> target == REFUNDED;
            case FAILED, CANCELLED, REFUNDED -> false;
        };
    }

    public boolean isTerminal() {
        for (PaymentStatus candidate : values()) {
            if (canTransitionTo(candidate)) {
                return false;
            }
        }
        return true;
    }
}

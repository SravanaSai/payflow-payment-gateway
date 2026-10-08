package com.payflow.common.core.ids;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identifier for a payment. Prevents accidentally passing a merchant ID
 * where a payment ID is expected — the compiler catches the mistake.
 */
public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PaymentId generate() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId of(String uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return new PaymentId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

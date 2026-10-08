package com.payflow.common.core.ids;

import java.util.Objects;
import java.util.UUID;

/** Typed identifier for a merchant account. */
public record MerchantId(UUID value) {

    public MerchantId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static MerchantId generate() {
        return new MerchantId(UUID.randomUUID());
    }

    public static MerchantId of(String uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return new MerchantId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

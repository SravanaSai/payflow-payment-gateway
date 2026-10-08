package com.payflow.common.core.ids;

import java.util.Objects;
import java.util.UUID;

/** Typed identifier for an API key row (internal UUID, not the public ak_live_ handle). */
public record ApiKeyId(UUID value) {

    public ApiKeyId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ApiKeyId generate() {
        return new ApiKeyId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

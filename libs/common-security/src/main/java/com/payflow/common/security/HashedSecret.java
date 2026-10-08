package com.payflow.common.security;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * A stored API key hash together with the pepper version that produced it.
 *
 * <p>The two travel as one value because separating them breaks rotation: a
 * hash whose pepper version is unknown can never be verified again.
 *
 * <p>Deliberately not a record — the generated {@code equals} would compare
 * {@code byte[]} by identity, and a naive content comparison would leak timing
 * information. See {@link #equals(Object)}.
 */
public final class HashedSecret {

    /** HMAC-SHA256 output width. A different length signals a truncation bug. */
    private static final int HASH_BYTES = 32;

    private final byte[] value;
    private final int pepperVersion;

    private HashedSecret(byte[] value, int pepperVersion) {
        if (value.length != HASH_BYTES) {
            throw new IllegalArgumentException(
                    "hash must be exactly " + HASH_BYTES + " bytes, got: " + value.length);
        }
        if (pepperVersion < 1) {
            throw new IllegalArgumentException("pepperVersion must be >= 1, got: " + pepperVersion);
        }
        this.value = value;
        this.pepperVersion = pepperVersion;
    }

    public static HashedSecret of(byte[] value, int pepperVersion) {
        Objects.requireNonNull(value, "value must not be null");
        return new HashedSecret(value.clone(), pepperVersion);
    }

    /** Defensive copy — callers must not be able to mutate stored hash bytes. */
    public byte[] value() {
        return value.clone();
    }

    public int pepperVersion() {
        return pepperVersion;
    }

    public String toBase64() {
        return Base64.getEncoder().encodeToString(value);
    }

    /**
     * Constant-time comparison. Implemented this way so that even code which
     * reaches for {@code equals} to verify a credential cannot leak, via
     * response timing, how many leading bytes of a guess were correct.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HashedSecret that)) {
            return false;
        }
        return pepperVersion == that.pepperVersion && MessageDigest.isEqual(value, that.value);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(value) + pepperVersion;
    }

    @Override
    public String toString() {
        return "HashedSecret[pepperVersion=" + pepperVersion
                + ", value=" + toBase64().substring(0, 8) + "...]";
    }
}

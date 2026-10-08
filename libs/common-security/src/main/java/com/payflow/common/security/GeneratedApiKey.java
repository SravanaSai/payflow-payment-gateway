package com.payflow.common.security;

import java.util.Objects;

/**
 * A freshly issued API key, returned to the merchant exactly once.
 *
 * <p>We store only a hash of {@link #secret()}, so this is the only moment the
 * plaintext exists on our side. If the merchant loses it they must rotate.
 *
 * <p>{@link #keyId()} is a public handle — safe to log, display and send in
 * headers. {@link #secret()} is the bearer credential and must never be logged.
 */
public record GeneratedApiKey(String keyId, String secret) {

    /** Number of leading secret characters safe to persist for display purposes. */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    public GeneratedApiKey {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        if (secret.length() <= DISPLAY_PREFIX_LENGTH) {
            throw new IllegalArgumentException("secret is too short to be a valid API key");
        }
    }

    /**
     * Non-sensitive fragment ({@code sk_live_9Xy4}) persisted alongside the hash
     * so merchants can tell their keys apart in a dashboard. Exposing 4 random
     * characters leaves the remaining entropy far beyond brute-force range.
     */
    public String secretPrefix() {
        return secret.substring(0, DISPLAY_PREFIX_LENGTH);
    }

    /**
     * Overridden because the record default would print every component —
     * including the secret — into any log statement that touches this object.
     */
    @Override
    public String toString() {
        return "GeneratedApiKey[keyId=" + keyId + ", secret=REDACTED]";
    }
}

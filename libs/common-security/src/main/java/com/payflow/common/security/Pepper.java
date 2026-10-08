package com.payflow.common.security;

import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * A server-wide secret mixed into every API key hash.
 *
 * <p>Unlike a salt — which is per-record and stored next to the hash — a pepper
 * is a single value held <em>outside</em> the database (environment variable
 * locally, Vault or KMS in production). A leaked database therefore yields
 * hashes an attacker cannot even test candidate secrets against offline.
 *
 * <p>Peppers are versioned because rotating one invalidates every existing hash:
 * the original secrets are gone, so hashes cannot be recomputed. Versioning lets
 * old hashes stay verifiable while new ones use the current pepper.
 *
 * <p>Deliberately not a record — the generated {@code toString} would print the
 * key material and the generated {@code equals} would compare array identity.
 */
public final class Pepper {

    /** 256 bits, matching the HMAC-SHA256 block strength it feeds. */
    private static final int MINIMUM_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final int version;
    private final SecretKeySpec key;

    private Pepper(int version, byte[] material) {
        if (version < 1) {
            throw new IllegalArgumentException("pepper version must be >= 1, got: " + version);
        }
        if (material.length < MINIMUM_BYTES) {
            throw new IllegalArgumentException(
                    "pepper must be at least " + MINIMUM_BYTES + " bytes, got: " + material.length);
        }
        this.version = version;
        this.key = new SecretKeySpec(material, HMAC_ALGORITHM);
    }

    /**
     * Builds a pepper from a Base64-encoded value, the form used in environment
     * variables. Generate one with {@code openssl rand -base64 32}.
     */
    public static Pepper fromBase64(int version, String base64Material) {
        Objects.requireNonNull(base64Material, "base64Material must not be null");
        byte[] material = Base64.getDecoder().decode(base64Material.trim());
        try {
            return new Pepper(version, material);
        } finally {
            // SecretKeySpec copied the bytes; wipe ours so the plaintext pepper
            // does not linger in the heap where a dump could expose it.
            Arrays.fill(material, (byte) 0);
        }
    }

    public int version() {
        return version;
    }

    /** Package-private: only {@link SecretHasher} may touch the key material. */
    SecretKeySpec key() {
        return key;
    }

    @Override
    public String toString() {
        return "Pepper[version=" + version + ", material=REDACTED]";
    }
}

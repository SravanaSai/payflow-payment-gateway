package com.payflow.common.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Generates API key material with cryptographically strong randomness.
 *
 * <p>Key strength here is the foundation of the whole authentication scheme:
 * a 256-bit random secret cannot be brute-forced regardless of how fast the
 * stored hash is to compute. That is precisely why {@link SecretHasher} uses a
 * fast keyed hash instead of a deliberately slow password hash such as Argon2 —
 * see {@code docs/adr/0001-api-key-storage-and-auth.md}.
 *
 * <p>Thread-safe: {@link SecureRandom} is safe for concurrent use.
 */
public final class ApiKeyGenerator {

    /** Public handle: 12 bytes = 96 bits, ample for a non-secret identifier. */
    private static final int KEY_ID_BYTES = 12;

    /** Secret: 32 bytes = 256 bits, matching the strength of HMAC-SHA256. */
    private static final int SECRET_BYTES = 32;

    private static final String KEY_ID_PREFIX = "ak";
    private static final String SECRET_PREFIX = "sk";

    /**
     * URL-safe alphabet (A-Z a-z 0-9 - _) with no padding, so keys can be
     * dropped into headers, URLs and config files without escaping.
     */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random;

    public ApiKeyGenerator() {
        // Default SecureRandom is /dev/urandom-backed and never blocks.
        // getInstanceStrong() may block on low kernel entropy — unacceptable
        // on a request-serving path.
        this(new SecureRandom());
    }

    public ApiKeyGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Issues a new key pair. The returned secret is the only copy in existence —
     * hash it and hand the plaintext to the merchant immediately.
     */
    public GeneratedApiKey generate(ApiKeyEnvironment environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return new GeneratedApiKey(
                token(KEY_ID_PREFIX, environment, KEY_ID_BYTES),
                token(SECRET_PREFIX, environment, SECRET_BYTES));
    }

    private String token(String prefix, ApiKeyEnvironment environment, int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return prefix + "_" + environment.token() + "_" + ENCODER.encodeToString(bytes);
    }
}

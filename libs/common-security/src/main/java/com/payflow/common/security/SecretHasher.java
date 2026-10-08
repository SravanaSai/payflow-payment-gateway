package com.payflow.common.security;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hashes and verifies API key secrets using HMAC-SHA256 keyed by a pepper.
 *
 * <p><strong>Why not Argon2 / bcrypt?</strong> Those exist to slow brute force
 * against low-entropy human passwords. Our secrets carry 256 bits of entropy
 * from {@link java.security.SecureRandom}, so brute force is already infeasible
 * by an astronomical margin and deliberate slowness buys no security — while
 * costing ~50-100ms and tens of megabytes per verification on the gateway's hot
 * path. Argon2 remains the right choice for a future human-login table.
 * See {@code docs/adr/0001-api-key-storage-and-auth.md}.
 *
 * <p><strong>Why keyed rather than plain SHA-256?</strong> An unkeyed hash lets
 * anyone holding the database test candidate secrets offline. The pepper lives
 * outside the database, so a dump alone is not enough.
 *
 * <p>Hashing is deterministic (no per-row salt), which lets the hash column be
 * indexed for direct lookup. Salts defend against rainbow tables over
 * low-entropy inputs; no such table can exist for 256-bit random values.
 *
 * <p>Thread-safe.
 */
public final class SecretHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final PepperRegistry peppers;

    public SecretHasher(PepperRegistry peppers) {
        this.peppers = Objects.requireNonNull(peppers, "peppers must not be null");
    }

    /**
     * Hashes a newly issued secret with the current pepper.
     *
     * <p>The secret is a {@code String} rather than {@code char[]} because it
     * arrives from an HTTP header as a String already — wiping our copy would
     * be theatre while the original sits in the request buffer.
     */
    public HashedSecret hash(String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        Pepper pepper = peppers.current();
        return HashedSecret.of(computeHmac(secret, pepper), pepper.version());
    }

    /**
     * One hash per known pepper version. The gateway sends all of these on lookup
     * so a key hashed with a previous pepper still authenticates during rotation.
     */
    public List<HashedSecret> hashAllVersions(String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        List<HashedSecret> hashes = new ArrayList<>();
        for (Pepper pepper : peppers.all()) {
            hashes.add(HashedSecret.of(computeHmac(secret, pepper), pepper.version()));
        }
        return List.copyOf(hashes);
    }

    /**
     * Verifies a candidate secret against a stored hash in constant time.
     *
     * <p>Returns {@code false} — never throws — when the stored hash references
     * a retired pepper version. An unverifiable credential is an authentication
     * failure, not a server error.
     */
    public boolean matches(String candidateSecret, HashedSecret stored) {
        Objects.requireNonNull(candidateSecret, "candidateSecret must not be null");
        Objects.requireNonNull(stored, "stored must not be null");

        Optional<Pepper> pepper = peppers.forVersion(stored.pepperVersion());
        if (pepper.isEmpty()) {
            return false;
        }

        byte[] computed = computeHmac(candidateSecret, pepper.get());
        // Constant-time: Arrays.equals would return at the first differing byte,
        // letting an attacker recover the hash one byte at a time from response
        // timing.
        return MessageDigest.isEqual(computed, stored.value());
    }

    /**
     * True when the stored hash predates the current pepper. Callers should
     * re-hash and persist after a successful {@link #matches} so retired
     * peppers can eventually be dropped.
     */
    public boolean needsRehash(HashedSecret stored) {
        Objects.requireNonNull(stored, "stored must not be null");
        return stored.pepperVersion() != peppers.currentVersion();
    }

    private byte[] computeHmac(String secret, Pepper pepper) {
        try {
            // Mac is stateful and not thread-safe, so never cache the instance.
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepper.key());
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory in every JDK — reaching here means the
            // runtime is broken, not that the request was bad.
            throw new IllegalStateException("HMAC-SHA256 unavailable in this JVM", e);
        }
    }
}

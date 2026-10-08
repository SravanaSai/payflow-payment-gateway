package com.payflow.common.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretHasherTest {

    private static final String SECRET = "payflow-test-secret-9Xy4bVc6dEf1gHi2jKl3mNo4pQr5sTu6vWx";

    private final SecretHasher hasher = new SecretHasher(PepperRegistry.single(pepper(1, 'a')));

    @Test
    void hashingIsDeterministic() {
        // Determinism is what allows the hash column to be indexed for lookup.
        assertEquals(hasher.hash(SECRET), hasher.hash(SECRET));
    }

    @Test
    void producesThirtyTwoByteHashes() {
        assertEquals(32, hasher.hash(SECRET).value().length);
    }

    @Test
    void differentSecretsProduceDifferentHashes() {
        assertNotEquals(hasher.hash(SECRET), hasher.hash(SECRET + "x"));
    }

    @Test
    void differentPeppersProduceDifferentHashes() {
        SecretHasher other = new SecretHasher(PepperRegistry.single(pepper(1, 'b')));

        // Proves the pepper is genuinely mixed in — a stolen database alone
        // cannot be used to test candidate secrets.
        assertNotEquals(hasher.hash(SECRET).toBase64(), other.hash(SECRET).toBase64());
    }

    @Test
    void stampsTheCurrentPepperVersion() {
        assertEquals(1, hasher.hash(SECRET).pepperVersion());
    }

    @Test
    void acceptsTheCorrectSecret() {
        assertTrue(hasher.matches(SECRET, hasher.hash(SECRET)));
    }

    @Test
    void rejectsAWrongSecret() {
        assertFalse(hasher.matches("payflow-wrong-secret", hasher.hash(SECRET)));
    }

    @Test
    void rejectsASecretDifferingOnlyInTheLastCharacter() {
        String nearMiss = SECRET.substring(0, SECRET.length() - 1) + "Z";
        assertFalse(hasher.matches(nearMiss, hasher.hash(SECRET)));
    }

    @Test
    void verifiesHashesCreatedWithAPreviousPepper() {
        HashedSecret legacy = new SecretHasher(PepperRegistry.single(pepper(1, 'a'))).hash(SECRET);

        SecretHasher rotated = new SecretHasher(
                PepperRegistry.of(List.of(pepper(1, 'a'), pepper(2, 'b')), 2));

        assertTrue(rotated.matches(SECRET, legacy), "rotation must not lock merchants out");
        assertTrue(rotated.needsRehash(legacy), "old hashes should be flagged for upgrade");
        assertEquals(2, rotated.hash(SECRET).pepperVersion(), "new hashes use the current pepper");
        assertFalse(rotated.needsRehash(rotated.hash(SECRET)));
    }

    @Test
    void hashAllVersionsCoversCurrentAndPreviousPeppers() {
        SecretHasher rotated = new SecretHasher(
                PepperRegistry.of(List.of(pepper(1, 'a'), pepper(2, 'b')), 2));

        var hashes = rotated.hashAllVersions(SECRET);
        assertEquals(2, hashes.size());
        assertTrue(hashes.stream().anyMatch(h -> h.pepperVersion() == 1));
        assertTrue(hashes.stream().anyMatch(h -> h.pepperVersion() == 2));
    }

    @Test
    void rejectsRatherThanThrowsWhenPepperVersionIsRetired() {
        HashedSecret orphaned = new SecretHasher(PepperRegistry.single(pepper(7, 'c'))).hash(SECRET);

        // Must be a failed authentication (401), never a server error (500).
        assertFalse(hasher.matches(SECRET, orphaned));
    }

    private static Pepper pepper(int version, char fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) fill);
        return Pepper.fromBase64(version, Base64.getEncoder().encodeToString(material));
    }
}

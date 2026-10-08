package com.payflow.common.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    @Test
    void generatesLiveKeysWithExpectedShape() {
        GeneratedApiKey key = generator.generate(ApiKeyEnvironment.LIVE);

        assertTrue(key.keyId().startsWith("ak_live_"), "key id should be environment tagged");
        assertTrue(key.secret().startsWith("sk_live_"), "secret should be environment tagged");
    }

    @Test
    void generatesTestKeysWithExpectedShape() {
        GeneratedApiKey key = generator.generate(ApiKeyEnvironment.TEST);

        assertTrue(key.keyId().startsWith("ak_test_"));
        assertTrue(key.secret().startsWith("sk_test_"));
    }

    @Test
    void secretCarries256BitsOfEntropy() {
        GeneratedApiKey key = generator.generate(ApiKeyEnvironment.LIVE);

        // Limit of 3 matters: the base64url alphabet itself contains '_'.
        String randomPart = key.secret().split("_", 3)[2];
        byte[] decoded = Base64.getUrlDecoder().decode(randomPart);

        assertEquals(32, decoded.length, "secret must be 256 bits — the basis for skipping Argon2");
    }

    @Test
    void generatesUniqueKeysAcrossManyCalls() {
        Set<String> secrets = new HashSet<>();
        Set<String> keyIds = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            GeneratedApiKey key = generator.generate(ApiKeyEnvironment.LIVE);
            secrets.add(key.secret());
            keyIds.add(key.keyId());
        }

        assertEquals(1_000, secrets.size(), "secrets must never repeat");
        assertEquals(1_000, keyIds.size(), "key ids must never repeat");
    }

    @Test
    void secretPrefixExposesOnlyDisplayFragment() {
        GeneratedApiKey key = generator.generate(ApiKeyEnvironment.LIVE);

        assertEquals(12, key.secretPrefix().length());
        assertTrue(key.secret().startsWith(key.secretPrefix()));
        assertTrue(key.secretPrefix().startsWith("sk_live_"));
    }

    @Test
    void toStringDoesNotLeakTheSecret() {
        GeneratedApiKey key = generator.generate(ApiKeyEnvironment.LIVE);

        String rendered = key.toString();

        assertFalse(rendered.contains(key.secret()), "secret must never reach a log statement");
        assertTrue(rendered.contains("REDACTED"));
        assertTrue(rendered.contains(key.keyId()), "key id is public and useful for debugging");
    }

    @Test
    void rejectsNullEnvironment() {
        assertThrows(NullPointerException.class, () -> generator.generate(null));
    }
}

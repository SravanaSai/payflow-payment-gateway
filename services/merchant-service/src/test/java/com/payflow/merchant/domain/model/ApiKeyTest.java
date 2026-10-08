package com.payflow.merchant.domain.model;

import com.payflow.common.core.ids.ApiKeyId;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.common.security.HashedSecret;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void activeKeyWithoutExpiryIsUsable() {
        ApiKey key = sampleKey(ApiKeyStatus.ACTIVE, null);
        assertTrue(key.isUsable(NOW));
    }

    @Test
    void revokedKeyIsNotUsable() {
        ApiKey key = sampleKey(ApiKeyStatus.REVOKED, null);
        assertFalse(key.isUsable(NOW));
    }

    @Test
    void expiredKeyIsNotUsable() {
        ApiKey key = sampleKey(ApiKeyStatus.ACTIVE, NOW.minusSeconds(1));
        assertFalse(key.isUsable(NOW));
    }

    @Test
    void keyWithFutureExpiryIsUsable() {
        ApiKey key = sampleKey(ApiKeyStatus.ACTIVE, NOW.plusSeconds(3600));
        assertTrue(key.isUsable(NOW));
    }

    private static ApiKey sampleKey(ApiKeyStatus status, Instant expiresAt) {
        byte[] hashBytes = new byte[32];
        Arrays.fill(hashBytes, (byte) 1);
        HashedSecret hash = HashedSecret.of(hashBytes, 1);
        return new ApiKey(
                ApiKeyId.generate(),
                MerchantId.generate(),
                "ak_live_testkeyid",
                ApiKeyEnvironment.LIVE,
                "sk_live_test",
                hash,
                status,
                expiresAt,
                null,
                NOW,
                NOW);
    }
}

package com.payflow.merchant.domain.model;

import com.payflow.common.core.ids.ApiKeyId;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.common.security.GeneratedApiKey;
import com.payflow.common.security.HashedSecret;

import java.time.Instant;
import java.util.Objects;

/**
 * API key aggregate — business rules for credential validity without JPA.
 */
public class ApiKey {

    private final ApiKeyId id;
    private final MerchantId merchantId;
    private final String keyId;
    private final ApiKeyEnvironment environment;
    private final String secretPrefix;
    private HashedSecret secretHash;
    private ApiKeyStatus status;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public ApiKey(ApiKeyId id, MerchantId merchantId, String keyId, ApiKeyEnvironment environment,
                  String secretPrefix, HashedSecret secretHash, ApiKeyStatus status,
                  Instant expiresAt, Instant lastUsedAt, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.merchantId = Objects.requireNonNull(merchantId);
        this.keyId = requireText(keyId, "keyId");
        this.environment = Objects.requireNonNull(environment);
        this.secretPrefix = requireText(secretPrefix, "secretPrefix");
        this.secretHash = Objects.requireNonNull(secretHash);
        this.status = Objects.requireNonNull(status);
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static ApiKey issue(MerchantId merchantId, GeneratedApiKey generated, HashedSecret hash, Instant now) {
        return new ApiKey(
                ApiKeyId.generate(),
                merchantId,
                generated.keyId(),
                environmentFromKey(generated),
                generated.secretPrefix(),
                hash,
                ApiKeyStatus.ACTIVE,
                null,
                null,
                now,
                now);
    }

    private static ApiKeyEnvironment environmentFromKey(GeneratedApiKey generated) {
        if (generated.keyId().contains("_live_")) {
            return ApiKeyEnvironment.LIVE;
        }
        if (generated.keyId().contains("_test_")) {
            return ApiKeyEnvironment.TEST;
        }
        throw new IllegalArgumentException("Unrecognized key environment in keyId: " + generated.keyId());
    }

    public boolean isUsable(Instant now) {
        if (status != ApiKeyStatus.ACTIVE) {
            return false;
        }
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return false;
        }
        return true;
    }

    public void revoke(Instant now) {
        this.status = ApiKeyStatus.REVOKED;
        this.updatedAt = now;
    }

    public void scheduleExpiry(Instant when, Instant now) {
        Objects.requireNonNull(when, "when must not be null");
        this.expiresAt = when;
        this.updatedAt = now;
    }

    public void recordUsage(Instant now) {
        this.lastUsedAt = now;
        this.updatedAt = now;
    }

    public void upgradeHash(HashedSecret newHash, Instant now) {
        this.secretHash = Objects.requireNonNull(newHash);
        this.updatedAt = now;
    }

    public ApiKeyId getId() {
        return id;
    }

    public MerchantId getMerchantId() {
        return merchantId;
    }

    public String getKeyId() {
        return keyId;
    }

    public ApiKeyEnvironment getEnvironment() {
        return environment;
    }

    public String getSecretPrefix() {
        return secretPrefix;
    }

    public HashedSecret getSecretHash() {
        return secretHash;
    }

    public ApiKeyStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

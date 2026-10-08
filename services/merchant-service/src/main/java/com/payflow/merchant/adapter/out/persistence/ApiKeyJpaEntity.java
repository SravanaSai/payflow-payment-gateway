package com.payflow.merchant.adapter.out.persistence;

import com.payflow.merchant.domain.model.ApiKeyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKeyJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "key_id", nullable = false, unique = true, length = 64)
    private String keyId;

    @Column(nullable = false, length = 16)
    private String environment;

    @Column(name = "secret_prefix", nullable = false, length = 16)
    private String secretPrefix;

    @Column(name = "secret_hash", nullable = false, unique = true, length = 64)
    private String secretHash;

    @Column(name = "pepper_version", nullable = false)
    private int pepperVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApiKeyStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApiKeyJpaEntity() {
    }

    public ApiKeyJpaEntity(
            UUID id,
            UUID merchantId,
            String keyId,
            String environment,
            String secretPrefix,
            String secretHash,
            int pepperVersion,
            ApiKeyStatus status,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.keyId = keyId;
        this.environment = environment;
        this.secretPrefix = secretPrefix;
        this.secretHash = secretHash;
        this.pepperVersion = pepperVersion;
        this.status = status;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getSecretPrefix() {
        return secretPrefix;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public int getPepperVersion() {
        return pepperVersion;
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

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public void setPepperVersion(int pepperVersion) {
        this.pepperVersion = pepperVersion;
    }

    public void setStatus(ApiKeyStatus status) {
        this.status = status;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

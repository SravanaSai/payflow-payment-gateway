package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.merchant.domain.model.ApiKey;
import com.payflow.merchant.domain.model.ApiKeyStatus;

import java.time.Instant;
import java.util.List;

/** Metadata-only view — never includes the secret or hash. */
public record ApiKeyResponse(
        String keyId,
        String secretPrefix,
        ApiKeyEnvironment environment,
        ApiKeyStatus status,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant createdAt) {

    public static ApiKeyResponse from(ApiKey apiKey) {
        return new ApiKeyResponse(
                apiKey.getKeyId(),
                apiKey.getSecretPrefix(),
                apiKey.getEnvironment(),
                apiKey.getStatus(),
                apiKey.getExpiresAt(),
                apiKey.getLastUsedAt(),
                apiKey.getCreatedAt());
    }

    public static List<ApiKeyResponse> fromList(List<ApiKey> apiKeys) {
        return apiKeys.stream().map(ApiKeyResponse::from).toList();
    }
}

package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.merchant.application.port.in.IssueApiKeyUseCase.IssuedApiKey;
import com.payflow.merchant.domain.model.ApiKey;

import java.time.Instant;

/**
 * Response for key issuance and rotation only — includes the plaintext secret once.
 * Deliberately separate from {@link ApiKeyResponse} so list endpoints cannot leak secrets.
 */
public record IssuedApiKeyResponse(
        String keyId,
        String secret,
        String secretPrefix,
        ApiKeyEnvironment environment,
        Instant createdAt) {

    public static IssuedApiKeyResponse from(IssuedApiKey issued) {
        ApiKey apiKey = issued.apiKey();
        return new IssuedApiKeyResponse(
                apiKey.getKeyId(),
                issued.plaintextSecret(),
                apiKey.getSecretPrefix(),
                apiKey.getEnvironment(),
                apiKey.getCreatedAt());
    }
}

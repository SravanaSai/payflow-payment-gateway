package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.merchant.application.port.in.LookupApiKeyUseCase.LookupResult;

import java.util.UUID;

/** Internal-only response for the edge gateway after hash lookup. */
public record ApiKeyLookupResponse(
        UUID merchantId,
        String keyId,
        ApiKeyEnvironment environment) {

    public static ApiKeyLookupResponse from(LookupResult result) {
        return new ApiKeyLookupResponse(
                result.merchantId().value(),
                result.keyId(),
                result.environment());
    }
}

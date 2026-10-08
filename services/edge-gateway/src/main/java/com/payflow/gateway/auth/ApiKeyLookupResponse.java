package com.payflow.gateway.auth;

import com.payflow.common.security.ApiKeyEnvironment;

import java.util.UUID;

public record ApiKeyLookupResponse(
        UUID merchantId,
        String keyId,
        ApiKeyEnvironment environment) {
}

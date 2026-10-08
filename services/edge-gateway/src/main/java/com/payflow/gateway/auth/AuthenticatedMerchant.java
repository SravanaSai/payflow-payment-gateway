package com.payflow.gateway.auth;

import com.payflow.common.security.ApiKeyEnvironment;

import java.util.UUID;

/** Identity resolved from merchant-service after a successful hash lookup. */
public record AuthenticatedMerchant(
        UUID merchantId,
        String keyId,
        ApiKeyEnvironment environment) {
}

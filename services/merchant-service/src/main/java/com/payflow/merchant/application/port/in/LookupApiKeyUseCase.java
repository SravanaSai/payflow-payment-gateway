package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;

import java.util.List;
import java.util.Optional;

/**
 * Inbound port — resolve a key from pre-computed hash candidates.
 * Used by the edge gateway, which holds the pepper and hashes the bearer secret.
 */
public interface LookupApiKeyUseCase {

    Optional<LookupResult> lookup(LookupCommand command);

    record LookupCommand(List<HashCandidate> candidates) {
    }

    record HashCandidate(int pepperVersion, String secretHashBase64) {
    }

    record LookupResult(
            MerchantId merchantId,
            String keyId,
            ApiKeyEnvironment environment) {
    }
}

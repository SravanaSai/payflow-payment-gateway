package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.merchant.domain.model.ApiKey;

/** Inbound port — issue a new API key for a merchant. */
public interface IssueApiKeyUseCase {

    IssuedApiKey issue(IssueApiKeyCommand command);

    record IssueApiKeyCommand(MerchantId merchantId, ApiKeyEnvironment environment) {
    }

    /** Plaintext secret exists only in this result — never persisted. */
    record IssuedApiKey(ApiKey apiKey, String plaintextSecret) {
    }
}

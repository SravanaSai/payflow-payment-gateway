package com.payflow.merchant.application.port.in;

import com.payflow.merchant.application.port.in.IssueApiKeyUseCase.IssuedApiKey;
import com.payflow.merchant.domain.model.Merchant;

/** Inbound port — create merchant use case. */
public interface CreateMerchantUseCase {

    CreatedMerchant create(CreateMerchantCommand command);

    record CreateMerchantCommand(String name, String email) {
    }

    /**
     * Onboarding result. The first TEST API key is issued in the same transaction
     * so a merchant can authenticate on the gateway immediately. The plaintext
     * secret is never persisted and is only present in this return value.
     */
    record CreatedMerchant(Merchant merchant, IssuedApiKey firstApiKey) {
    }
}

package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.merchant.application.port.in.CreateMerchantUseCase.CreatedMerchant;
import com.payflow.merchant.domain.model.Merchant;
import com.payflow.merchant.domain.model.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Onboarding response — includes the first TEST API key secret exactly once.
 * {@link MerchantResponse} is used for subsequent GETs so the secret cannot leak there.
 */
public record CreatedMerchantResponse(
        UUID id,
        String name,
        String email,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt,
        IssuedApiKeyResponse apiKey) {

    public static CreatedMerchantResponse from(CreatedMerchant created) {
        Merchant merchant = created.merchant();
        return new CreatedMerchantResponse(
                merchant.getId().value(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt(),
                IssuedApiKeyResponse.from(created.firstApiKey()));
    }
}

package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.merchant.domain.model.Merchant;
import com.payflow.merchant.domain.model.MerchantStatus;

import java.time.Instant;
import java.util.UUID;

/** API response DTO — decouples HTTP contract from domain model. */
public record MerchantResponse(
        UUID id,
        String name,
        String email,
        MerchantStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId().value(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }
}

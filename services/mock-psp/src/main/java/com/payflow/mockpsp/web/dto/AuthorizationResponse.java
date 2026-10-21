package com.payflow.mockpsp.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.payflow.mockpsp.authorization.Authorization;

import java.time.Instant;

public record AuthorizationResponse(
        String id,
        String status,
        @JsonProperty("amount_minor") long amountMinor,
        String currency,
        @JsonProperty("captured_amount_minor") long capturedAmountMinor,
        @JsonProperty("merchant_reference") String merchantReference,
        @JsonProperty("created_at") Instant createdAt) {

    public static AuthorizationResponse from(Authorization hold) {
        return new AuthorizationResponse(
                hold.id(),
                hold.status().wireValue(),
                hold.amountMinor(),
                hold.currency(),
                hold.capturedAmountMinor(),
                hold.merchantReference(),
                hold.createdAt());
    }
}

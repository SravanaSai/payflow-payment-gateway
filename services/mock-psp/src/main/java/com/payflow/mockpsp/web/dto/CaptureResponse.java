package com.payflow.mockpsp.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record CaptureResponse(
        String id,
        @JsonProperty("authorization_id") String authorizationId,
        String status,
        @JsonProperty("amount_minor") long amountMinor) {

    public static CaptureResponse succeeded(String authorizationId, long amountMinor) {
        return new CaptureResponse("cap_" + UUID.randomUUID(), authorizationId, "succeeded", amountMinor);
    }
}

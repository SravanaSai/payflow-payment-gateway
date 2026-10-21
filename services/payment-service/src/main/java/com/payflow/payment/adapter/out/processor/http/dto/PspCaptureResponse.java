package com.payflow.payment.adapter.out.processor.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PspCaptureResponse(
        String id,
        @JsonProperty("authorization_id") String authorizationId,
        String status) {
}

package com.payflow.payment.adapter.out.processor.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PspErrorResponse(Body error) {

    public record Body(
            String type,
            String code,
            @JsonProperty("decline_code") String declineCode,
            String message) {
    }
}

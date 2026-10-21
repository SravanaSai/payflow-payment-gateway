package com.payflow.payment.adapter.out.processor.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PspAuthorizeRequest(
        @JsonProperty("amount_minor") long amountMinor,
        String currency,
        @JsonProperty("merchant_reference") String merchantReference) {
}

package com.payflow.mockpsp.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The processor's wire format: snake_case field names, unlike PayFlow's own camelCase API. */
public record AuthorizeRequest(
        @JsonProperty("amount_minor") @Positive long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @JsonProperty("merchant_reference") @Size(max = 128) String merchantReference) {
}

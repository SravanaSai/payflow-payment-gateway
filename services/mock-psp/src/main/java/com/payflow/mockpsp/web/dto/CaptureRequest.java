package com.payflow.mockpsp.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

public record CaptureRequest(@JsonProperty("amount_minor") @Positive long amountMinor) {
}

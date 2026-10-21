package com.payflow.payment.adapter.out.processor.http.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PspCaptureRequest(@JsonProperty("amount_minor") long amountMinor) {
}

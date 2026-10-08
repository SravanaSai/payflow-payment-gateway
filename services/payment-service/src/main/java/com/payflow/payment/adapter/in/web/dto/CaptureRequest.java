package com.payflow.payment.adapter.in.web.dto;

import jakarta.validation.constraints.Positive;

/** Omit {@code amountMinor} to capture the full authorized amount. */
public record CaptureRequest(@Positive Long amountMinor) {
}

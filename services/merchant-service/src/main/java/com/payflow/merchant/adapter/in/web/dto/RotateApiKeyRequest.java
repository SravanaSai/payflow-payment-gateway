package com.payflow.merchant.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * @param gracePeriodHours hours the old key remains valid after rotation; 0 for immediate expiry
 */
public record RotateApiKeyRequest(
        @Min(0) @Max(168) Integer gracePeriodHours) {
}

package com.payflow.merchant.adapter.in.web.dto;

import com.payflow.common.security.ApiKeyEnvironment;
import jakarta.validation.constraints.NotNull;

public record IssueApiKeyRequest(@NotNull ApiKeyEnvironment environment) {
}

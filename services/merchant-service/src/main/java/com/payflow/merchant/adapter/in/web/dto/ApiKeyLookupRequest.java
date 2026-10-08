package com.payflow.merchant.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ApiKeyLookupRequest(@NotEmpty List<@Valid HashCandidateDto> candidates) {

    public record HashCandidateDto(
            @NotNull Integer pepperVersion,
            @NotBlank String secretHash) {
    }
}

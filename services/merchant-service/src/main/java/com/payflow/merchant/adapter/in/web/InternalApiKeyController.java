package com.payflow.merchant.adapter.in.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.merchant.adapter.in.web.dto.ApiKeyLookupRequest;
import com.payflow.merchant.adapter.in.web.dto.ApiKeyLookupResponse;
import com.payflow.merchant.application.port.in.LookupApiKeyUseCase;
import com.payflow.merchant.application.port.in.LookupApiKeyUseCase.HashCandidate;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Internal endpoint for the edge gateway — not exposed publicly in Step 3 routing.
 * The gateway hashes the bearer secret and sends candidate hashes here.
 */
@RestController
@RequestMapping("/internal/v1/api-keys")
@Hidden
public class InternalApiKeyController {

    private final LookupApiKeyUseCase lookupApiKeyUseCase;

    public InternalApiKeyController(LookupApiKeyUseCase lookupApiKeyUseCase) {
        this.lookupApiKeyUseCase = lookupApiKeyUseCase;
    }

    @PostMapping("/lookup")
    public ApiKeyLookupResponse lookup(@Valid @RequestBody ApiKeyLookupRequest request) {
        List<HashCandidate> candidates = request.candidates().stream()
                .map(dto -> new HashCandidate(dto.pepperVersion(), dto.secretHash()))
                .toList();
        return lookupApiKeyUseCase.lookup(new LookupApiKeyUseCase.LookupCommand(candidates))
                .map(ApiKeyLookupResponse::from)
                .orElseThrow(() -> new PayFlowException(ErrorCode.API_KEY_NOT_FOUND));
    }
}

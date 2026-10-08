package com.payflow.merchant.adapter.in.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.adapter.in.web.dto.ApiKeyResponse;
import com.payflow.merchant.adapter.in.web.dto.IssueApiKeyRequest;
import com.payflow.merchant.adapter.in.web.dto.IssuedApiKeyResponse;
import com.payflow.merchant.adapter.in.web.dto.RotateApiKeyRequest;
import com.payflow.merchant.application.port.in.IssueApiKeyUseCase;
import com.payflow.merchant.application.port.in.ListApiKeysUseCase;
import com.payflow.merchant.application.port.in.RevokeApiKeyUseCase;
import com.payflow.merchant.application.port.in.RotateApiKeyUseCase;
import com.payflow.merchant.application.service.ApiKeyApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/merchants/{merchantId}/api-keys")
@Tag(name = "API Keys", description = "Merchant API key management")
public class ApiKeyController {

    private final IssueApiKeyUseCase issueApiKeyUseCase;
    private final RotateApiKeyUseCase rotateApiKeyUseCase;
    private final RevokeApiKeyUseCase revokeApiKeyUseCase;
    private final ListApiKeysUseCase listApiKeysUseCase;

    public ApiKeyController(
            IssueApiKeyUseCase issueApiKeyUseCase,
            RotateApiKeyUseCase rotateApiKeyUseCase,
            RevokeApiKeyUseCase revokeApiKeyUseCase,
            ListApiKeysUseCase listApiKeysUseCase) {
        this.issueApiKeyUseCase = issueApiKeyUseCase;
        this.rotateApiKeyUseCase = rotateApiKeyUseCase;
        this.revokeApiKeyUseCase = revokeApiKeyUseCase;
        this.listApiKeysUseCase = listApiKeysUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue a new API key", description = "The secret is returned exactly once.")
    public IssuedApiKeyResponse issue(
            @PathVariable("merchantId") UUID merchantId,
            @Valid @RequestBody IssueApiKeyRequest request) {
        var issued = issueApiKeyUseCase.issue(new IssueApiKeyUseCase.IssueApiKeyCommand(
                new MerchantId(merchantId), request.environment()));
        return IssuedApiKeyResponse.from(issued);
    }

    @GetMapping
    @Operation(summary = "List API keys for a merchant")
    public List<ApiKeyResponse> list(@PathVariable("merchantId") UUID merchantId) {
        return ApiKeyResponse.fromList(listApiKeysUseCase.listByMerchant(new MerchantId(merchantId)));
    }

    @PostMapping("/{keyId}/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Rotate an API key", description = "Issues a new key and schedules expiry on the old one.")
    public IssuedApiKeyResponse rotate(
            @PathVariable("merchantId") UUID merchantId,
            @PathVariable("keyId") String keyId,
            @Valid @RequestBody(required = false) RotateApiKeyRequest request) {
        int graceHours = request != null && request.gracePeriodHours() != null
                ? request.gracePeriodHours()
                : ApiKeyApplicationService.DEFAULT_GRACE_PERIOD_HOURS;
        var issued = rotateApiKeyUseCase.rotate(new RotateApiKeyUseCase.RotateApiKeyCommand(
                new MerchantId(merchantId), keyId, graceHours));
        return IssuedApiKeyResponse.from(issued);
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke an API key immediately")
    public void revoke(@PathVariable("merchantId") UUID merchantId, @PathVariable("keyId") String keyId) {
        try {
            revokeApiKeyUseCase.revoke(new RevokeApiKeyUseCase.RevokeApiKeyCommand(
                    new MerchantId(merchantId), keyId));
        } catch (PayFlowException ex) {
            if (ex.getErrorCode() == ErrorCode.API_KEY_NOT_FOUND) {
                return;
            }
            throw ex;
        }
    }
}

package com.payflow.merchant.adapter.in.web;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.adapter.in.web.dto.CreateMerchantRequest;
import com.payflow.merchant.adapter.in.web.dto.CreatedMerchantResponse;
import com.payflow.merchant.adapter.in.web.dto.MerchantResponse;
import com.payflow.merchant.application.port.in.CreateMerchantUseCase;
import com.payflow.merchant.application.port.in.GetMerchantUseCase;
import com.payflow.merchant.domain.model.Merchant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/merchants")
@Tag(name = "Merchants", description = "Merchant onboarding")
public class MerchantController {

    private final CreateMerchantUseCase createMerchantUseCase;
    private final GetMerchantUseCase getMerchantUseCase;

    public MerchantController(
            CreateMerchantUseCase createMerchantUseCase,
            GetMerchantUseCase getMerchantUseCase) {
        this.createMerchantUseCase = createMerchantUseCase;
        this.getMerchantUseCase = getMerchantUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a merchant",
            description = "Issues the first TEST API key in the same response. The secret is returned exactly once.")
    public CreatedMerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        return CreatedMerchantResponse.from(createMerchantUseCase.create(
                new CreateMerchantUseCase.CreateMerchantCommand(request.name(), request.email())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get merchant by id")
    public MerchantResponse get(@PathVariable("id") UUID id) {
        Merchant merchant = getMerchantUseCase.getById(new MerchantId(id));
        return MerchantResponse.from(merchant);
    }
}

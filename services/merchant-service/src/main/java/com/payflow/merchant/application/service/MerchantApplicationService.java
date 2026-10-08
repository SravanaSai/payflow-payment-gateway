package com.payflow.merchant.application.service;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.merchant.application.port.in.CreateMerchantUseCase;
import com.payflow.merchant.application.port.in.GetMerchantUseCase;
import com.payflow.merchant.application.port.in.IssueApiKeyUseCase;
import com.payflow.merchant.application.port.out.MerchantRepositoryPort;
import com.payflow.merchant.domain.model.Merchant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@Transactional(noRollbackFor = PayFlowException.class)
public class MerchantApplicationService implements CreateMerchantUseCase, GetMerchantUseCase {

    private final MerchantRepositoryPort merchantRepository;
    private final IssueApiKeyUseCase issueApiKeyUseCase;
    private final Clock clock;

    public MerchantApplicationService(
            MerchantRepositoryPort merchantRepository,
            IssueApiKeyUseCase issueApiKeyUseCase,
            Clock clock) {
        this.merchantRepository = merchantRepository;
        this.issueApiKeyUseCase = issueApiKeyUseCase;
        this.clock = clock;
    }

    @Override
    public CreatedMerchant create(CreateMerchantCommand command) {
        Instant now = clock.instant();
        Merchant merchant = merchantRepository.save(Merchant.create(command.name(), command.email(), now));
        IssueApiKeyUseCase.IssuedApiKey firstKey = issueApiKeyUseCase.issue(
                new IssueApiKeyUseCase.IssueApiKeyCommand(merchant.getId(), ApiKeyEnvironment.TEST));
        return new CreatedMerchant(merchant, firstKey);
    }

    @Override
    public Merchant getById(MerchantId id) {
        return merchantRepository.findById(id)
                .orElseThrow(() -> new PayFlowException(ErrorCode.MERCHANT_NOT_FOUND));
    }
}

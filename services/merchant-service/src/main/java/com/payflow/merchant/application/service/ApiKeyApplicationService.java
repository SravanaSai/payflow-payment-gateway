package com.payflow.merchant.application.service;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.common.security.ApiKeyGenerator;
import com.payflow.common.security.GeneratedApiKey;
import com.payflow.common.security.HashedSecret;
import com.payflow.common.security.PepperRegistry;
import com.payflow.common.security.SecretHasher;
import com.payflow.merchant.application.port.in.IssueApiKeyUseCase;
import com.payflow.merchant.application.port.in.ListApiKeysUseCase;
import com.payflow.merchant.application.port.in.LookupApiKeyUseCase;
import com.payflow.merchant.application.port.in.RevokeApiKeyUseCase;
import com.payflow.merchant.application.port.in.RotateApiKeyUseCase;
import com.payflow.merchant.application.port.out.ApiKeyRepositoryPort;
import com.payflow.merchant.application.port.out.MerchantRepositoryPort;
import com.payflow.merchant.domain.model.ApiKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(noRollbackFor = PayFlowException.class)
public class ApiKeyApplicationService implements
        IssueApiKeyUseCase,
        RotateApiKeyUseCase,
        RevokeApiKeyUseCase,
        ListApiKeysUseCase,
        LookupApiKeyUseCase {

    public static final int DEFAULT_GRACE_PERIOD_HOURS = 24;

    private final MerchantRepositoryPort merchantRepository;
    private final ApiKeyRepositoryPort apiKeyRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final SecretHasher secretHasher;
    private final PepperRegistry pepperRegistry;
    private final Clock clock;

    public ApiKeyApplicationService(
            MerchantRepositoryPort merchantRepository,
            ApiKeyRepositoryPort apiKeyRepository,
            ApiKeyGenerator apiKeyGenerator,
            SecretHasher secretHasher,
            PepperRegistry pepperRegistry,
            Clock clock) {
        this.merchantRepository = merchantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyGenerator = apiKeyGenerator;
        this.secretHasher = secretHasher;
        this.pepperRegistry = pepperRegistry;
        this.clock = clock;
    }

    @Override
    public IssuedApiKey issue(IssueApiKeyCommand command) {
        requireMerchant(command.merchantId());
        return issueNewKey(command.merchantId(), command.environment());
    }

    @Override
    public IssuedApiKey rotate(RotateApiKeyCommand command) {
        requireMerchant(command.merchantId());
        ApiKey existing = findKeyForMerchant(command.merchantId(), command.keyId());

        Instant now = clock.instant();
        existing.scheduleExpiry(now.plus(command.gracePeriodHours(), ChronoUnit.HOURS), now);
        apiKeyRepository.save(existing);

        return issueNewKey(command.merchantId(), existing.getEnvironment());
    }

    @Override
    public void revoke(RevokeApiKeyCommand command) {
        requireMerchant(command.merchantId());
        ApiKey apiKey = findKeyForMerchant(command.merchantId(), command.keyId());
        Instant now = clock.instant();
        apiKey.revoke(now);
        apiKeyRepository.save(apiKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKey> listByMerchant(MerchantId merchantId) {
        requireMerchant(merchantId);
        return apiKeyRepository.findByMerchantId(merchantId);
    }

    @Override
    public Optional<LookupResult> lookup(LookupCommand command) {
        Instant now = clock.instant();
        List<HashCandidate> candidates = command.candidates().stream()
                .sorted(Comparator.comparingInt(HashCandidate::pepperVersion).reversed())
                .toList();

        for (HashCandidate candidate : candidates) {
            Optional<ApiKey> match = apiKeyRepository.findBySecretHashAndPepperVersion(
                    candidate.secretHashBase64(), candidate.pepperVersion());
            if (match.isEmpty()) {
                continue;
            }

            ApiKey apiKey = match.get();
            if (!apiKey.isUsable(now)) {
                return Optional.empty();
            }

            maybeUpgradeHash(apiKey, candidates, now);
            apiKey.recordUsage(now);
            apiKeyRepository.save(apiKey);

            return Optional.of(new LookupResult(
                    apiKey.getMerchantId(),
                    apiKey.getKeyId(),
                    apiKey.getEnvironment()));
        }
        return Optional.empty();
    }

    private void maybeUpgradeHash(ApiKey apiKey, List<HashCandidate> candidates, Instant now) {
        if (!secretHasher.needsRehash(apiKey.getSecretHash())) {
            return;
        }
        int currentVersion = pepperRegistry.currentVersion();
        candidates.stream()
                .filter(candidate -> candidate.pepperVersion() == currentVersion)
                .findFirst()
                .ifPresent(candidate -> apiKey.upgradeHash(
                        decodeHash(candidate.secretHashBase64(), candidate.pepperVersion()), now));
    }

    private IssuedApiKey issueNewKey(MerchantId merchantId, ApiKeyEnvironment environment) {
        GeneratedApiKey generated = apiKeyGenerator.generate(environment);
        HashedSecret hash = secretHasher.hash(generated.secret());
        Instant now = clock.instant();
        ApiKey apiKey = ApiKey.issue(merchantId, generated, hash, now);
        ApiKey saved = apiKeyRepository.save(apiKey);
        return new IssuedApiKey(saved, generated.secret());
    }

    private void requireMerchant(MerchantId merchantId) {
        merchantRepository.findById(merchantId)
                .orElseThrow(() -> new PayFlowException(ErrorCode.MERCHANT_NOT_FOUND));
    }

    private ApiKey findKeyForMerchant(MerchantId merchantId, String keyId) {
        return apiKeyRepository.findByMerchantIdAndKeyId(merchantId, keyId)
                .orElseThrow(() -> new PayFlowException(ErrorCode.API_KEY_NOT_FOUND));
    }

    private static HashedSecret decodeHash(String base64, int pepperVersion) {
        return HashedSecret.of(Base64.getDecoder().decode(base64), pepperVersion);
    }
}

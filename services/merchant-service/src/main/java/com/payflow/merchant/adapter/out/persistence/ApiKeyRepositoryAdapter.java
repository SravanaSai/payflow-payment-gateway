package com.payflow.merchant.adapter.out.persistence;

import com.payflow.common.core.ids.ApiKeyId;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.common.security.HashedSecret;
import com.payflow.merchant.application.port.out.ApiKeyRepositoryPort;
import com.payflow.merchant.domain.model.ApiKey;
import com.payflow.merchant.domain.model.ApiKeyStatus;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Component
public class ApiKeyRepositoryAdapter implements ApiKeyRepositoryPort {

    private final ApiKeyJpaRepository jpaRepository;

    public ApiKeyRepositoryAdapter(ApiKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        ApiKeyJpaEntity entity = jpaRepository.findById(apiKey.getId().value())
                .map(existing -> updateEntity(existing, apiKey))
                .orElseGet(() -> toEntity(apiKey));
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public List<ApiKey> findByMerchantId(MerchantId merchantId) {
        return jpaRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<ApiKey> findByMerchantIdAndKeyId(MerchantId merchantId, String keyId) {
        return jpaRepository.findByMerchantIdAndKeyId(merchantId.value(), keyId).map(this::toDomain);
    }

    @Override
    public Optional<ApiKey> findBySecretHashAndPepperVersion(String secretHashBase64, int pepperVersion) {
        return jpaRepository.findBySecretHashAndPepperVersion(secretHashBase64, pepperVersion)
                .map(this::toDomain);
    }

    private ApiKeyJpaEntity updateEntity(ApiKeyJpaEntity entity, ApiKey apiKey) {
        entity.setSecretHash(apiKey.getSecretHash().toBase64());
        entity.setPepperVersion(apiKey.getSecretHash().pepperVersion());
        entity.setStatus(apiKey.getStatus());
        entity.setExpiresAt(apiKey.getExpiresAt());
        entity.setLastUsedAt(apiKey.getLastUsedAt());
        entity.setUpdatedAt(apiKey.getUpdatedAt());
        return entity;
    }

    private ApiKeyJpaEntity toEntity(ApiKey apiKey) {
        return new ApiKeyJpaEntity(
                apiKey.getId().value(),
                apiKey.getMerchantId().value(),
                apiKey.getKeyId(),
                apiKey.getEnvironment().name(),
                apiKey.getSecretPrefix(),
                apiKey.getSecretHash().toBase64(),
                apiKey.getSecretHash().pepperVersion(),
                apiKey.getStatus(),
                apiKey.getExpiresAt(),
                apiKey.getLastUsedAt(),
                apiKey.getCreatedAt(),
                apiKey.getUpdatedAt());
    }

    private ApiKey toDomain(ApiKeyJpaEntity entity) {
        return new ApiKey(
                new ApiKeyId(entity.getId()),
                new MerchantId(entity.getMerchantId()),
                entity.getKeyId(),
                ApiKeyEnvironment.valueOf(entity.getEnvironment()),
                entity.getSecretPrefix(),
                HashedSecret.of(Base64.getDecoder().decode(entity.getSecretHash()), entity.getPepperVersion()),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getLastUsedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

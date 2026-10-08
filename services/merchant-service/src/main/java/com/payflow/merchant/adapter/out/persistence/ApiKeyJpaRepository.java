package com.payflow.merchant.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyJpaEntity, UUID> {

    List<ApiKeyJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<ApiKeyJpaEntity> findByMerchantIdAndKeyId(UUID merchantId, String keyId);

    Optional<ApiKeyJpaEntity> findBySecretHashAndPepperVersion(String secretHash, int pepperVersion);
}

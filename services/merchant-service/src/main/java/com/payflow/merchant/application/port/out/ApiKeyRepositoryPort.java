package com.payflow.merchant.application.port.out;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.domain.model.ApiKey;

import java.util.List;
import java.util.Optional;

/** Outbound port — API key persistence. */
public interface ApiKeyRepositoryPort {

    ApiKey save(ApiKey apiKey);

    List<ApiKey> findByMerchantId(MerchantId merchantId);

    Optional<ApiKey> findByMerchantIdAndKeyId(MerchantId merchantId, String keyId);

    Optional<ApiKey> findBySecretHashAndPepperVersion(String secretHashBase64, int pepperVersion);
}

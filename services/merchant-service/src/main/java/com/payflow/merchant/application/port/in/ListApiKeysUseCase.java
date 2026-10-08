package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.domain.model.ApiKey;

import java.util.List;

/** Inbound port — list API keys for a merchant (metadata only, no secrets). */
public interface ListApiKeysUseCase {

    List<ApiKey> listByMerchant(MerchantId merchantId);
}

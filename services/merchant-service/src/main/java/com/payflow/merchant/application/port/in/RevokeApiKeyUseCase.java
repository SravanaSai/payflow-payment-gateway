package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;

/** Inbound port — immediately revoke an API key. */
public interface RevokeApiKeyUseCase {

    void revoke(RevokeApiKeyCommand command);

    record RevokeApiKeyCommand(MerchantId merchantId, String keyId) {
    }
}

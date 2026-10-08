package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.application.port.in.IssueApiKeyUseCase.IssuedApiKey;

/** Inbound port — rotate an API key with a grace window on the old credential. */
public interface RotateApiKeyUseCase {

    IssuedApiKey rotate(RotateApiKeyCommand command);

    record RotateApiKeyCommand(MerchantId merchantId, String keyId, int gracePeriodHours) {
    }
}

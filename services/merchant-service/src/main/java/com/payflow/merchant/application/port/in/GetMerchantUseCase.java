package com.payflow.merchant.application.port.in;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.domain.model.Merchant;

/** Inbound port — fetch merchant by id. */
public interface GetMerchantUseCase {

    Merchant getById(MerchantId id);
}

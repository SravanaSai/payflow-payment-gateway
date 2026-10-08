package com.payflow.merchant.application.port.out;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.domain.model.Merchant;

import java.util.Optional;

/** Outbound port — persistence abstraction (hexagonal architecture). */
public interface MerchantRepositoryPort {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(MerchantId id);
}

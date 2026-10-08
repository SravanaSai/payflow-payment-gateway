package com.payflow.payment.domain.model;

import com.payflow.common.core.ids.MerchantId;

import java.util.Objects;

/**
 * The data partition a payment belongs to: one merchant, in one environment.
 * Callers only ever see payments whose owner equals their own, so a TEST key
 * can never read or move a LIVE payment.
 */
public record PaymentOwner(MerchantId merchantId, PaymentEnvironment environment) {

    public PaymentOwner {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
    }
}

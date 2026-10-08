package com.payflow.payment.application.port.out;

import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.Optional;

/**
 * Outbound port — payment persistence (hexagonal architecture).
 * There is deliberately no unscoped lookup: every read names the owner it is for.
 */
public interface PaymentRepositoryPort {

    /**
     * Persists the payment and returns its stored state, including the new version.
     * Continue with the returned instance; the argument's version is stale afterwards.
     *
     * @throws com.payflow.common.core.error.PayFlowException with {@code CONCURRENT_MODIFICATION}
     *         when another request updated the same payment first
     */
    Payment save(Payment payment);

    Optional<Payment> findById(PaymentId id, PaymentOwner owner);
}

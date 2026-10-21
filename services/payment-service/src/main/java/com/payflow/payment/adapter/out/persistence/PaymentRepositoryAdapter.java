package com.payflow.payment.adapter.out.persistence;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps between the domain {@link Payment} and its JPA row — anti-corruption at the boundary.
 * JPA types and Spring persistence exceptions stop here; callers only see the port.
 */
@Component
class PaymentRepositoryAdapter implements PaymentRepositoryPort {

    private final PaymentJpaRepository jpaRepository;

    PaymentRepositoryAdapter(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // saveAndFlush, not save: flushing bumps the version before the result is mapped back,
    // and surfaces a lost-update conflict here, where it can be translated, not at commit.
    @Override
    public Payment save(Payment payment) {
        try {
            return toDomain(jpaRepository.saveAndFlush(toEntity(payment)));
        } catch (OptimisticLockingFailureException ex) {
            throw new PayFlowException(ErrorCode.CONCURRENT_MODIFICATION,
                    "Payment " + payment.getId() + " was modified by a concurrent request", ex);
        }
    }

    @Override
    public Optional<Payment> findById(PaymentId id, PaymentOwner owner) {
        return jpaRepository.findByIdAndMerchantIdAndEnvironment(
                        id.value(), owner.merchantId().value(), owner.environment())
                .map(PaymentRepositoryAdapter::toDomain);
    }

    private static PaymentJpaEntity toEntity(Payment payment) {
        return new PaymentJpaEntity(
                payment.getId().value(),
                payment.getOwner().merchantId().value(),
                payment.getOwner().environment(),
                payment.getStatus(),
                payment.getAmount().minorUnits(),
                payment.getAmount().currency(),
                payment.getCapturedAmount().minorUnits(),
                payment.getDescription(),
                payment.getReference(),
                payment.getFailureReason(),
                payment.getCancellationReason(),
                payment.getProcessorReference(),
                payment.getCaptureAttempts(),
                payment.getVersion(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    private static Payment toDomain(PaymentJpaEntity entity) {
        return Payment.rehydrate(
                new PaymentId(entity.getId()),
                new PaymentOwner(new MerchantId(entity.getMerchantId()), entity.getEnvironment()),
                Money.ofMinorUnits(entity.getAmountMinor(), entity.getCurrency()),
                Money.ofMinorUnits(entity.getCapturedAmountMinor(), entity.getCurrency()),
                entity.getStatus(),
                entity.getDescription(),
                entity.getReference(),
                entity.getFailureReason(),
                entity.getCancellationReason(),
                entity.getProcessorReference(),
                entity.getCaptureAttempts(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

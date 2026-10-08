package com.payflow.merchant.adapter.out.persistence;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.merchant.application.port.out.MerchantRepositoryPort;
import com.payflow.merchant.domain.model.Merchant;
import com.payflow.merchant.domain.model.MerchantStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Maps between domain {@link Merchant} and JPA entity — anti-corruption at the boundary. */
@Component
public class MerchantRepositoryAdapter implements MerchantRepositoryPort {

    private final MerchantJpaRepository jpaRepository;

    public MerchantRepositoryAdapter(MerchantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Merchant save(Merchant merchant) {
        MerchantJpaEntity entity = toEntity(merchant);
        MerchantJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Merchant> findById(MerchantId id) {
        return jpaRepository.findById(id.value()).map(this::toDomain);
    }

    private MerchantJpaEntity toEntity(Merchant merchant) {
        return new MerchantJpaEntity(
                merchant.getId().value(),
                merchant.getName(),
                merchant.getEmail(),
                merchant.getStatus(),
                merchant.getCreatedAt(),
                merchant.getUpdatedAt());
    }

    private Merchant toDomain(MerchantJpaEntity entity) {
        return new Merchant(
                new MerchantId(entity.getId()),
                entity.getName(),
                entity.getEmail(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

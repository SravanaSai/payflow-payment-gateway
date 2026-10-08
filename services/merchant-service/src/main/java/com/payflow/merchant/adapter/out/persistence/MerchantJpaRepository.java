package com.payflow.merchant.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantJpaRepository extends JpaRepository<MerchantJpaEntity, UUID> {
}

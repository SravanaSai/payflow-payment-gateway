package com.payflow.merchant.domain.model;

import com.payflow.common.core.ids.MerchantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Merchant aggregate root — pure domain object with no JPA annotations.
 * Persistence mapping lives in the adapter layer.
 */
public class Merchant {

    private final MerchantId id;
    private final String name;
    private final String email;
    private MerchantStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public Merchant(MerchantId id, String name, String email, MerchantStatus status,
                    Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.name = requireText(name, "name");
        this.email = requireText(email, "email");
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static Merchant create(String name, String email, Instant now) {
        return new Merchant(MerchantId.generate(), name, email, MerchantStatus.PENDING, now, now);
    }

    public void activate(Instant now) {
        if (status == MerchantStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot activate a suspended merchant");
        }
        this.status = MerchantStatus.ACTIVE;
        this.updatedAt = now;
    }

    public MerchantId getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

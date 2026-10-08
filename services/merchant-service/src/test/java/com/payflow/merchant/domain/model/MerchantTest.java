package com.payflow.merchant.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerchantTest {

    @Test
    void createStartsAsPending() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Merchant merchant = Merchant.create("Acme Corp", "ops@acme.com", now);

        assertEquals(MerchantStatus.PENDING, merchant.getStatus());
        assertEquals("Acme Corp", merchant.getName());
    }

    @Test
    void activateUpdatesStatus() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Merchant merchant = Merchant.create("Acme", "ops@acme.com", now);
        merchant.activate(Instant.parse("2026-01-02T00:00:00Z"));

        assertEquals(MerchantStatus.ACTIVE, merchant.getStatus());
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> Merchant.create("  ", "ops@acme.com", Instant.now()));
    }
}

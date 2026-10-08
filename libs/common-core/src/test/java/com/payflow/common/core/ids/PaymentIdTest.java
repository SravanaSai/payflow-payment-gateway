package com.payflow.common.core.ids;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentIdTest {

    @Test
    void generatesNonNullId() {
        PaymentId id = PaymentId.generate();
        assertNotNull(id.value());
    }

    @Test
    void parsesFromString() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        PaymentId id = PaymentId.of("550e8400-e29b-41d4-a716-446655440000");
        assertEquals(uuid, id.value());
    }
}

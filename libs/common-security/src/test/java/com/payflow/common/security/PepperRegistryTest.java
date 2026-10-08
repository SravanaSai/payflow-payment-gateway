package com.payflow.common.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PepperRegistryTest {

    @Test
    void rejectsPepperShorterThanThirtyTwoBytes() {
        String weak = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalArgumentException.class, () -> Pepper.fromBase64(1, weak));
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> pepper(0, 'a'));
    }

    @Test
    void pepperToStringDoesNotLeakMaterial() {
        String rendered = pepper(1, 'a').toString();

        assertTrue(rendered.contains("version=1"));
        assertTrue(rendered.contains("REDACTED"));
    }

    @Test
    void singleExposesItsOnlyPepperAsCurrent() {
        PepperRegistry registry = PepperRegistry.single(pepper(3, 'a'));

        assertEquals(3, registry.currentVersion());
        assertEquals(3, registry.current().version());
        assertTrue(registry.forVersion(3).isPresent());
    }

    @Test
    void retainsPreviousPeppersDuringRotation() {
        PepperRegistry registry = PepperRegistry.of(List.of(pepper(1, 'a'), pepper(2, 'b')), 2);

        assertEquals(2, registry.currentVersion());
        assertTrue(registry.forVersion(1).isPresent(), "old hashes must stay verifiable");
        assertTrue(registry.forVersion(2).isPresent());
    }

    @Test
    void returnsEmptyForRetiredVersion() {
        PepperRegistry registry = PepperRegistry.single(pepper(2, 'a'));

        assertFalse(registry.forVersion(1).isPresent());
    }

    @Test
    void rejectsDuplicateVersions() {
        List<Pepper> duplicates = List.of(pepper(1, 'a'), pepper(1, 'b'));

        assertThrows(IllegalArgumentException.class, () -> PepperRegistry.of(duplicates, 1));
    }

    @Test
    void rejectsCurrentVersionThatWasNotSupplied() {
        List<Pepper> peppers = List.of(pepper(1, 'a'));

        // Misconfiguration must fail at startup, not on the first request.
        assertThrows(IllegalArgumentException.class, () -> PepperRegistry.of(peppers, 2));
    }

    @Test
    void rejectsEmptyRegistry() {
        assertThrows(IllegalArgumentException.class, () -> PepperRegistry.of(List.of(), 1));
    }

    private static Pepper pepper(int version, char fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, (byte) fill);
        return Pepper.fromBase64(version, Base64.getEncoder().encodeToString(material));
    }
}

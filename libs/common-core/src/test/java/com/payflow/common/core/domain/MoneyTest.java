package com.payflow.common.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void storesAmountInMinorUnits() {
        Money money = Money.ofMinorUnits(1050, Currency.INR);
        assertEquals(1050, money.minorUnits());
        assertEquals(Currency.INR, money.currency());
    }

    @Test
    void addSameCurrency() {
        Money a = Money.ofMinorUnits(1000, Currency.INR);
        Money b = Money.ofMinorUnits(50, Currency.INR);
        assertEquals(Money.ofMinorUnits(1050, Currency.INR), a.add(b));
    }

    @Test
    void subtractSameCurrency() {
        Money a = Money.ofMinorUnits(1000, Currency.INR);
        Money b = Money.ofMinorUnits(250, Currency.INR);
        assertEquals(Money.ofMinorUnits(750, Currency.INR), a.subtract(b));
    }

    @Test
    void rejectsNegativeMinorUnits() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinorUnits(-1, Currency.INR));
    }

    @Test
    void rejectsCurrencyMismatchOnAdd() {
        Money inr = Money.ofMinorUnits(100, Currency.INR);
        Money usd = Money.ofMinorUnits(100, Currency.USD);
        assertThrows(IllegalArgumentException.class, () -> inr.add(usd));
    }

    @Test
    void rejectsSubtractionBelowZero() {
        Money a = Money.ofMinorUnits(100, Currency.INR);
        Money b = Money.ofMinorUnits(200, Currency.INR);
        assertThrows(IllegalArgumentException.class, () -> a.subtract(b));
    }

    @Test
    void isZeroAndComparison() {
        Money zero = Money.ofMinorUnits(0, Currency.USD);
        Money ten = Money.ofMinorUnits(1000, Currency.USD);
        assertTrue(zero.isZero());
        assertFalse(ten.isZero());
        assertTrue(ten.isGreaterThan(zero));
    }
}

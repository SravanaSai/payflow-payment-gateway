package com.payflow.common.core.domain;

import java.util.Objects;

/**
 * Immutable money value stored in minor units (paise, cents).
 * Never use {@code double} for money — floating point causes rounding bugs.
 *
 * <p>Example: ₹10.50 is stored as {@code minorUnits=1050, currency=INR}.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        if (minorUnits < 0) {
            throw new IllegalArgumentException("minorUnits must be >= 0, got: " + minorUnits);
        }
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money ofMinorUnits(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(minorUnits + other.minorUnits, currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        long result = minorUnits - other.minorUnits;
        if (result < 0) {
            throw new IllegalArgumentException(
                    "Subtraction would result in negative amount: " + minorUnits + " - " + other.minorUnits);
        }
        return new Money(result, currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minorUnits > other.minorUnits;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}

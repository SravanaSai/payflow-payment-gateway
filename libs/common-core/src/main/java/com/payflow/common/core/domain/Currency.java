package com.payflow.common.core.domain;

/**
 * ISO 4217 currency codes supported by PayFlow.
 * Using an enum prevents invalid strings like "IN" or "rupees".
 */
public enum Currency {
    INR("356", 2),
    USD("840", 2),
    EUR("978", 2),
    GBP("826", 2);

    private final String numericCode;
    private final int defaultFractionDigits;

    Currency(String numericCode, int defaultFractionDigits) {
        this.numericCode = numericCode;
        this.defaultFractionDigits = defaultFractionDigits;
    }

    public String getNumericCode() {
        return numericCode;
    }

    public int getDefaultFractionDigits() {
        return defaultFractionDigits;
    }
}

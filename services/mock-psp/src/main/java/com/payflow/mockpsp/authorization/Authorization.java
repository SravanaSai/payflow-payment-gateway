package com.payflow.mockpsp.authorization;

import java.time.Instant;

/** A hold the processor placed on funds. Immutable; capturing produces a new instance. */
public record Authorization(
        String id,
        long amountMinor,
        String currency,
        String merchantReference,
        Status status,
        long capturedAmountMinor,
        Instant createdAt) {

    public enum Status {
        APPROVED("approved"),
        CAPTURED("captured");

        private final String wireValue;

        Status(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    Authorization captured(long amountMinor) {
        return new Authorization(id, this.amountMinor, currency, merchantReference,
                Status.CAPTURED, amountMinor, createdAt);
    }
}

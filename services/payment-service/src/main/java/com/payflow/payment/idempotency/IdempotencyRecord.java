package com.payflow.payment.idempotency;

import java.time.Instant;

/** One stored key. {@code response} is present exactly when the status is COMPLETED. */
record IdempotencyRecord(String fingerprint, Status status, StoredResponse response, Instant expiresAt) {

    enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    /** Everything needed to replay a response byte for byte. */
    record StoredResponse(int httpStatus, String contentType, String location, byte[] body) {
    }
}

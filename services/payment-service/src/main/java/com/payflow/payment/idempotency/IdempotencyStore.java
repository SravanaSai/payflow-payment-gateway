package com.payflow.payment.idempotency;

import com.payflow.payment.idempotency.IdempotencyRecord.StoredResponse;

import java.util.Optional;

/**
 * Persistence for the claim-then-complete protocol. Each method commits on its own: duplicates
 * see a claim immediately instead of waiting on it, and a claim survives the business work
 * rolling back, so a failed request's response can still be stored.
 */
interface IdempotencyStore {

    /** @return true if the caller now owns the key: it was free, or its previous record had expired */
    boolean claim(IdempotencyKey key, String fingerprint);

    Optional<IdempotencyRecord> find(IdempotencyKey key);

    void complete(IdempotencyKey key, StoredResponse response);

    void release(IdempotencyKey key);
}

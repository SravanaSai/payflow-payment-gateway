package com.payflow.payment.idempotency;

import com.payflow.payment.config.IdempotencyProperties;
import com.payflow.payment.idempotency.IdempotencyRecord.Status;
import com.payflow.payment.idempotency.IdempotencyRecord.StoredResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL: the claim is a single atomic upsert, which JPA cannot express. */
@Component
class JdbcIdempotencyStore implements IdempotencyStore {

    // One statement decides the race: it inserts a fresh claim, or recycles a record that has
    // expired, and otherwise changes nothing. One affected row means the caller owns the key.
    private static final String CLAIM = """
            INSERT INTO idempotency_records (merchant_id, environment, idempotency_key,
                                             request_fingerprint, status, created_at, expires_at)
            VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
            ON CONFLICT (merchant_id, environment, idempotency_key) DO UPDATE
                SET request_fingerprint = EXCLUDED.request_fingerprint,
                    status = 'IN_PROGRESS',
                    response_status = NULL,
                    response_content_type = NULL,
                    response_location = NULL,
                    response_body = NULL,
                    created_at = EXCLUDED.created_at,
                    expires_at = EXCLUDED.expires_at
                WHERE idempotency_records.expires_at <= EXCLUDED.created_at
            """;

    private static final String FIND = """
            SELECT request_fingerprint, status, response_status, response_content_type,
                   response_location, response_body, expires_at
            FROM idempotency_records
            WHERE merchant_id = ? AND environment = ? AND idempotency_key = ?
            """;

    private static final String COMPLETE = """
            UPDATE idempotency_records
            SET status = 'COMPLETED', response_status = ?, response_content_type = ?,
                response_location = ?, response_body = ?
            WHERE merchant_id = ? AND environment = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
            """;

    private static final String RELEASE = """
            DELETE FROM idempotency_records
            WHERE merchant_id = ? AND environment = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
            """;

    // Bounded batches keep each delete's locks and transaction short, however much has expired.
    private static final String DELETE_EXPIRED = """
            DELETE FROM idempotency_records
            WHERE (merchant_id, environment, idempotency_key) IN (
                SELECT merchant_id, environment, idempotency_key
                FROM idempotency_records
                WHERE expires_at <= ?
                ORDER BY expires_at
                LIMIT ?)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration ttl;

    JdbcIdempotencyStore(JdbcTemplate jdbc, Clock clock, IdempotencyProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = properties.ttl();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(IdempotencyKey key, String fingerprint) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jdbc.update(CLAIM, merchantId(key), environment(key), key.value(),
                fingerprint, now, now.plus(ttl)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(IdempotencyKey key) {
        return jdbc.query(FIND, JdbcIdempotencyStore::toRecord, merchantId(key), environment(key), key.value())
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(IdempotencyKey key, StoredResponse response) {
        jdbc.update(COMPLETE, response.httpStatus(), response.contentType(), response.location(), response.body(),
                merchantId(key), environment(key), key.value());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyKey key) {
        jdbc.update(RELEASE, merchantId(key), environment(key), key.value());
    }

    /** Deletes up to {@code limit} records that expired at or before {@code cutoff}; returns how many. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(Instant cutoff, int limit) {
        return jdbc.update(DELETE_EXPIRED, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC), limit);
    }

    private static UUID merchantId(IdempotencyKey key) {
        return key.owner().merchantId().value();
    }

    private static String environment(IdempotencyKey key) {
        return key.owner().environment().name();
    }

    private static IdempotencyRecord toRecord(ResultSet row, int rowNumber) throws SQLException {
        Status status = Status.valueOf(row.getString("status"));
        StoredResponse response = status == Status.COMPLETED
                ? new StoredResponse(row.getInt("response_status"), row.getString("response_content_type"),
                        row.getString("response_location"), row.getBytes("response_body"))
                : null;
        return new IdempotencyRecord(row.getString("request_fingerprint"), status, response,
                row.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}

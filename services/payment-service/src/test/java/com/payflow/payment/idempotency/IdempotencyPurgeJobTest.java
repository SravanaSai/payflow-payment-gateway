package com.payflow.payment.idempotency;

import com.payflow.payment.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A batch size of 2 makes five expired records take three batches, so the loop itself is under test. */
@SpringBootTest
@TestPropertySource(properties = "payflow.idempotency.purge.batch-size=2")
class IdempotencyPurgeJobTest extends PostgresTestSupport {

    @Autowired
    private IdempotencyPurgeJob purgeJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID merchantId = UUID.randomUUID();

    @Test
    void deletesEveryExpiredRecordInBatchesAndKeepsLiveOnes() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            insert("expired-" + i, now.minus(Duration.ofHours(1)));
        }
        for (int i = 0; i < 2; i++) {
            insert("live-" + i, now.plus(Duration.ofHours(1)));
        }

        int purged = purgeJob.purgeExpired();

        assertThat(purged).isGreaterThanOrEqualTo(5);
        assertThat(recordsLike("expired-%")).isZero();
        assertThat(recordsLike("live-%")).isEqualTo(2);
    }

    private void insert(String key, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO idempotency_records (merchant_id, environment, idempotency_key,
                                                 request_fingerprint, status, created_at, expires_at)
                VALUES (?, 'TEST', ?, ?, 'IN_PROGRESS', ?, ?)
                """, merchantId, key, "f".repeat(64),
                OffsetDateTime.ofInstant(expiresAt.minus(Duration.ofHours(24)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    }

    private int recordsLike(String keyPattern) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE merchant_id = ? AND idempotency_key LIKE ?",
                Integer.class, merchantId, keyPattern);
    }
}

package com.payflow.payment.idempotency;

import com.payflow.payment.idempotency.IdempotencyRecord.Status;
import com.payflow.payment.idempotency.IdempotencyRecord.StoredResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Decorator that answers retries of completed requests from Redis, so a retry storm reaches
 * Postgres once per key instead of once per retry. Postgres stays the source of truth: claims
 * are decided there, only COMPLETED records are cached, and any Redis failure counts as a miss.
 */
final class CachingIdempotencyStore implements IdempotencyStore {

    // Bump the version when the cached shape changes; entries in the old shape then just expire.
    private static final String KEY_PREFIX = "idem:v1:";

    private static final Logger log = LoggerFactory.getLogger(CachingIdempotencyStore.class);

    private final IdempotencyStore delegate;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration maxTtl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    CachingIdempotencyStore(
            IdempotencyStore delegate,
            StringRedisTemplate redis,
            JsonMapper json,
            Clock clock,
            Duration maxTtl,
            MeterRegistry meters) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.maxTtl = maxTtl;
        this.hits = meters.counter("payflow.idempotency.cache", "result", "hit");
        this.misses = meters.counter("payflow.idempotency.cache", "result", "miss");
        this.errors = meters.counter("payflow.idempotency.cache", "result", "error");
    }

    // A cached record is COMPLETED and unexpired, so the key is held: exactly what Postgres would answer.
    @Override
    public boolean claim(IdempotencyKey key, String fingerprint) {
        return read(key).isEmpty() && delegate.claim(key, fingerprint);
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKey key) {
        Optional<IdempotencyRecord> cached = read(key);
        if (cached.isPresent()) {
            hits.increment();
            return cached;
        }
        misses.increment();
        Optional<IdempotencyRecord> stored = delegate.find(key);
        stored.filter(record -> record.status() == Status.COMPLETED).ifPresent(record -> write(key, record));
        return stored;
    }

    @Override
    public void complete(IdempotencyKey key, StoredResponse response) {
        delegate.complete(key, response);
    }

    @Override
    public void release(IdempotencyKey key) {
        delegate.release(key);
    }

    private Optional<IdempotencyRecord> read(IdempotencyKey key) {
        try {
            String value = redis.opsForValue().get(redisKey(key));
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, IdempotencyRecord.class));
        } catch (RuntimeException ex) {
            degraded("read", key, ex);
            return Optional.empty();
        }
    }

    // Capped at the record's remaining life, so Redis can never answer for a key Postgres has forgotten.
    private void write(IdempotencyKey key, IdempotencyRecord record) {
        Duration remaining = Duration.between(clock.instant(), record.expiresAt());
        Duration ttl = remaining.compareTo(maxTtl) < 0 ? remaining : maxTtl;
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(redisKey(key), json.writeValueAsString(record), ttl);
        } catch (RuntimeException ex) {
            degraded("write", key, ex);
        }
    }

    private void degraded(String operation, IdempotencyKey key, RuntimeException ex) {
        errors.increment();
        log.warn("Idempotency cache {} failed for {} {}; falling back to Postgres: {}",
                operation, IdempotencyFilter.KEY_HEADER, key.value(), ex.toString());
    }

    static String redisKey(IdempotencyKey key) {
        return KEY_PREFIX + key.owner().merchantId() + ":" + key.owner().environment() + ":" + key.value();
    }
}

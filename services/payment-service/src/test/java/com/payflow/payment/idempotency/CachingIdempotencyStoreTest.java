package com.payflow.payment.idempotency;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.payment.PostgresRedisTestSupport;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import com.payflow.payment.idempotency.IdempotencyRecord.Status;
import com.payflow.payment.idempotency.IdempotencyRecord.StoredResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** The decorator against real Redis, with a recording fake standing in for the Postgres store. */
class CachingIdempotencyStoreTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration MAX_TTL = Duration.ofMinutes(10);
    private static final String FINGERPRINT = "f".repeat(64);
    private static final byte[] BODY = "{\"id\":\"p-1\"}".getBytes(UTF_8);

    private static LettuceConnectionFactory liveConnection;
    private static StringRedisTemplate liveRedis;

    private final RecordingStore postgres = new RecordingStore();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final IdempotencyKey key = new IdempotencyKey(
            new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST), UUID.randomUUID().toString());

    @BeforeAll
    static void connect() {
        liveConnection = connectionTo(
                PostgresRedisTestSupport.REDIS.getHost(), PostgresRedisTestSupport.REDIS.getMappedPort(6379));
        liveRedis = new StringRedisTemplate(liveConnection);
    }

    @AfterAll
    static void disconnect() {
        liveConnection.destroy();
    }

    @BeforeEach
    void flushRedis() {
        liveRedis.getConnectionFactory().getConnection().serverCommands().flushDb();
        postgres.reset();
        meters.clear();
    }

    @Test
    void aCompletedRecordIsServedFromRedisWithoutTouchingPostgres() {
        postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));
        CachingIdempotencyStore cache = cacheOver(liveRedis);

        cache.find(key);
        boolean claimed = cache.claim(key, FINGERPRINT);
        Optional<IdempotencyRecord> fromRedis = cache.find(key);

        assertThat(claimed).isFalse();
        assertThat(postgres.claims).as("claims that reached Postgres").isZero();
        assertThat(postgres.finds).as("finds that reached Postgres").isEqualTo(1);
        assertThat(fromRedis).hasValueSatisfying(record -> {
            assertThat(record.fingerprint()).isEqualTo(FINGERPRINT);
            assertThat(record.status()).isEqualTo(Status.COMPLETED);
            assertThat(record.response().httpStatus()).isEqualTo(201);
            assertThat(record.response().location()).isEqualTo("/v1/payments/p-1");
            assertThat(record.response().body()).containsExactly(BODY);
        });
        assertThat(count("miss")).isEqualTo(1);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    void inProgressRecordsAreNeverCached() {
        postgres.put(key, new IdempotencyRecord(FINGERPRINT, Status.IN_PROGRESS, null, NOW.plus(Duration.ofHours(24))));
        CachingIdempotencyStore cache = cacheOver(liveRedis);

        cache.find(key);
        cache.find(key);

        assertThat(postgres.finds).isEqualTo(2);
        assertThat(liveRedis.hasKey(CachingIdempotencyStore.redisKey(key))).isFalse();
    }

    @Test
    void anEntryNeverOutlivesItsRecordInPostgres() {
        postgres.put(key, completed(NOW.plusSeconds(5)));

        cacheOver(liveRedis).find(key);

        assertThat(ttlMillis()).isPositive().isLessThanOrEqualTo(5_000);
    }

    @Test
    void anEntryIsCappedAtTheCacheTtl() {
        postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));

        cacheOver(liveRedis).find(key);

        assertThat(ttlMillis()).isPositive().isLessThanOrEqualTo(MAX_TTL.toMillis());
    }

    @Test
    void aBlackHoleRedisOpensTheBreakerAndLaterCallsSkipIt() throws Exception {
        postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));
        try (ServerSocket blackHole = new ServerSocket(0)) {
            LettuceConnectionFactory hanging = connectionTo("127.0.0.1", blackHole.getLocalPort());
            CircuitBreaker breaker = testBreaker();
            CachingIdempotencyStore cache = cacheOver(new StringRedisTemplate(hanging), breaker);
            try {
                for (int i = 0; i < 4; i++) {
                    cache.find(key);
                }
                assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

                long start = System.nanoTime();
                assertThat(cache.find(key)).isPresent();
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                assertThat(elapsedMs).isLessThan(20);
                assertThat(count("skipped")).isGreaterThanOrEqualTo(1);
            } finally {
                hanging.destroy();
            }
        }
    }

    @Test
    void aCorruptCacheEntryDoesNotOpenTheBreaker() {
        postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));
        CircuitBreaker breaker = testBreaker();
        CachingIdempotencyStore cache = cacheOver(liveRedis, breaker);

        for (int i = 0; i < 10; i++) {
            liveRedis.opsForValue().set(CachingIdempotencyStore.redisKey(key), "{not-json");
            assertThat(cache.find(key)).isPresent();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(count("error")).isGreaterThanOrEqualTo(10);
    }

    @Test
    void anOpenBreakerRecoversWhenRedisAnswers() throws InterruptedException {
        postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));
        CircuitBreaker breaker = recoveryTestBreaker();
        CachingIdempotencyStore cache = cacheOver(liveRedis, breaker);

        cache.find(key);
        breaker.transitionToOpenState();
        assertThat(cache.find(key)).isPresent();
        assertThat(count("skipped")).isGreaterThanOrEqualTo(1);

        Thread.sleep(150);
        for (int i = 0; i < 5 && breaker.getState() != CircuitBreaker.State.CLOSED; i++) {
            cache.find(key);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(count("hit")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRedisOutageFallsBackToPostgres() throws IOException {
        LettuceConnectionFactory dead = connectionTo("127.0.0.1", closedPort());
        try {
            postgres.put(key, completed(NOW.plus(Duration.ofHours(24))));
            CachingIdempotencyStore cache = cacheOver(new StringRedisTemplate(dead));

            assertThat(cache.claim(key, FINGERPRINT)).isFalse();
            assertThat(cache.find(key)).isPresent();
            assertThat(postgres.claims).isEqualTo(1);
            assertThat(postgres.finds).isEqualTo(1);
            assertThat(count("error")).isGreaterThanOrEqualTo(2);
        } finally {
            dead.destroy();
        }
    }

    private CachingIdempotencyStore cacheOver(StringRedisTemplate redis) {
        return cacheOver(redis, testBreaker());
    }

    private CachingIdempotencyStore cacheOver(StringRedisTemplate redis, CircuitBreaker breaker) {
        return new CachingIdempotencyStore(postgres, redis, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), MAX_TTL, breaker, meters);
    }

    private static CircuitBreaker testBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        return CircuitBreaker.of("test-idempotency-cache", config);
    }

    private static CircuitBreaker recoveryTestBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreaker.of("test-idempotency-cache-recovery", config);
    }

    private long ttlMillis() {
        return liveRedis.getExpire(CachingIdempotencyStore.redisKey(key), TimeUnit.MILLISECONDS);
    }

    private double count(String result) {
        return meters.counter("payflow.idempotency.cache", "result", result).count();
    }

    private static IdempotencyRecord completed(Instant expiresAt) {
        return new IdempotencyRecord(FINGERPRINT, Status.COMPLETED,
                new StoredResponse(201, "application/json", "/v1/payments/p-1", BODY), expiresAt);
    }

    private static LettuceConnectionFactory connectionTo(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                        .build())
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Stands in for the Postgres store and counts how often the cache had to ask it. */
    private static final class RecordingStore implements IdempotencyStore {

        private final Map<IdempotencyKey, IdempotencyRecord> records = new HashMap<>();
        private int claims;
        private int finds;

        void put(IdempotencyKey key, IdempotencyRecord record) {
            records.put(key, record);
        }

        void reset() {
            records.clear();
            claims = 0;
            finds = 0;
        }

        @Override
        public boolean claim(IdempotencyKey key, String fingerprint) {
            claims++;
            return records.putIfAbsent(key,
                    new IdempotencyRecord(fingerprint, Status.IN_PROGRESS, null, NOW.plus(Duration.ofHours(24)))) == null;
        }

        @Override
        public Optional<IdempotencyRecord> find(IdempotencyKey key) {
            finds++;
            return Optional.ofNullable(records.get(key));
        }

        @Override
        public void complete(IdempotencyKey key, StoredResponse response) {
            records.computeIfPresent(key, (k, record) ->
                    new IdempotencyRecord(record.fingerprint(), Status.COMPLETED, response, record.expiresAt()));
        }

        @Override
        public void release(IdempotencyKey key) {
            records.remove(key);
        }
    }
}

package com.payflow.gateway.ratelimit;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.gateway.RedisTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TokenBucketLimiterTest extends RedisTestSupport {

    @Autowired
    TokenBucketLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void allowsBurstUpToCapacityThenDenies() {
        RateLimitPolicy policy = new RateLimitPolicy("test", 5, 10);

        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = limiter.tryConsume("rl:test:burst", policy);
            assertThat(decision.allowed()).isTrue();
        }

        RateLimitDecision denied = limiter.tryConsume("rl:test:burst", policy);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy("test", 2, 5);

        assertThat(limiter.tryConsume("rl:test:refill", policy).allowed()).isTrue();
        assertThat(limiter.tryConsume("rl:test:refill", policy).allowed()).isTrue();
        assertThat(limiter.tryConsume("rl:test:refill", policy).allowed()).isFalse();

        Thread.sleep(250);

        assertThat(limiter.tryConsume("rl:test:refill", policy).allowed()).isTrue();
    }

    @Test
    void distinctKeysDoNotInterfere() {
        RateLimitPolicy policy = new RateLimitPolicy("test", 1, 1);

        assertThat(limiter.tryConsume("rl:test:a", policy).allowed()).isTrue();
        assertThat(limiter.tryConsume("rl:test:a", policy).allowed()).isFalse();
        assertThat(limiter.tryConsume("rl:test:b", policy).allowed()).isTrue();
    }

    @Test
    void concurrentRequestsRespectCapacity() throws InterruptedException {
        RateLimitPolicy policy = new RateLimitPolicy("test", 10, 100);
        AtomicInteger allowed = new AtomicInteger();
        int threads = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryConsume("rl:test:concurrent", policy).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get()).isEqualTo(10);
    }

    @Test
    void setsTtlOnBucket() {
        RateLimitPolicy policy = new RateLimitPolicy("test", 5, 1);
        limiter.tryConsume("rl:test:ttl", policy);

        Long ttl = redis.getExpire("rl:test:ttl");
        assertThat(ttl).isNotNull();
        assertThat(ttl).isGreaterThan(0);
    }

    @Test
    void failsOpenWhenRedisErrors() {
        StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
        stubRedisExecuteThrows(failingRedis);

        DefaultRedisScript<List<Long>> script = mock(DefaultRedisScript.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenBucketLimiter failingLimiter = new TokenBucketLimiter(
                failingRedis, script, CircuitBreakerRegistry.ofDefaults(), registry);

        RateLimitPolicy policy = new RateLimitPolicy("test", 10, 1);
        RateLimitDecision decision = failingLimiter.tryConsume("rl:test:failopen", policy);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isTrue();
        assertThat(registry.get("payflow.ratelimit.degraded").counter().count()).isEqualTo(1);
        verify(failingRedis, times(1)).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void repeatedRedisErrorsOpenTheBreakerAndSkipRedis() {
        StringRedisTemplate failingRedis = mock(StringRedisTemplate.class);
        stubRedisExecuteThrows(failingRedis);

        @SuppressWarnings("unchecked")
        DefaultRedisScript<List<Long>> script = mock(DefaultRedisScript.class);
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .build();
        CircuitBreakerRegistry breakerRegistry = CircuitBreakerRegistry.of(breakerConfig);
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(TokenBucketLimiter.BREAKER);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TokenBucketLimiter failingLimiter = new TokenBucketLimiter(failingRedis, script, breakerRegistry, registry);

        RateLimitPolicy policy = new RateLimitPolicy("test", 10, 1);
        for (int i = 0; i < 6; i++) {
            RateLimitDecision decision = failingLimiter.tryConsume("rl:test:breaker", policy);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.degraded()).isTrue();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        verify(failingRedis, times(4)).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static void stubRedisExecuteThrows(StringRedisTemplate failingRedis) {
        doThrow(new RuntimeException("redis down"))
                .when(failingRedis)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }
}

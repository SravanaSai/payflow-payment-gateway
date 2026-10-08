package com.payflow.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Executes the token-bucket Lua script in Redis. Fails open on Redis errors so
 * a cache outage never blocks legitimate payment traffic.
 */
@Component
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);
    private static final int REQUEST_COST = 1;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List<Long>> script;
    private final Counter degradedCounter;

    public TokenBucketLimiter(
            StringRedisTemplate redis,
            DefaultRedisScript<List<Long>> tokenBucketScript,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.script = tokenBucketScript;
        this.degradedCounter = Counter.builder("payflow.ratelimit.degraded")
                .description("Rate-limit checks that failed open due to Redis errors")
                .register(meterRegistry);
    }

    public RateLimitDecision tryConsume(String redisKey, RateLimitPolicy policy) {
        try {
            List<Long> result = redis.execute(
                    script,
                    List.of(redisKey),
                    String.valueOf(policy.capacity()),
                    String.valueOf(policy.refillPerSecond()),
                    String.valueOf(REQUEST_COST),
                    String.valueOf(policy.ttlSeconds()));

            if (result == null || result.size() < 3) {
                log.warn("Unexpected token-bucket script result for key {} — failing open", redisKey);
                return failOpen(policy);
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = Math.max(0, result.get(1));
            long retryAfterSeconds = Math.max(1, (result.get(2) + 999) / 1000);

            if (allowed) {
                return RateLimitDecision.allowed(policy.capacity(), remaining);
            }
            return RateLimitDecision.denied(policy.capacity(), remaining, retryAfterSeconds);
        } catch (Exception ex) {
            log.warn("Redis rate-limit check failed for key {} — failing open", redisKey, ex);
            return failOpen(policy);
        }
    }

    private RateLimitDecision failOpen(RateLimitPolicy policy) {
        degradedCounter.increment();
        return RateLimitDecision.degradedAllowed(policy.capacity());
    }
}

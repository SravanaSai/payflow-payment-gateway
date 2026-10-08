package com.payflow.payment.idempotency;

import com.payflow.payment.config.IdempotencyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Wraps the Postgres store in the Redis cache. The filter asks for an {@link IdempotencyStore}
 * and gets the decorator; with the cache disabled it gets the Postgres store directly.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payflow.idempotency.cache", name = "enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyCacheConfiguration {

    @Bean
    @Primary
    IdempotencyStore cachingIdempotencyStore(
            JdbcIdempotencyStore postgres,
            StringRedisTemplate redis,
            JsonMapper json,
            Clock clock,
            IdempotencyProperties properties,
            MeterRegistry meters) {
        return new CachingIdempotencyStore(postgres, redis, json, clock, properties.cache().ttl(), meters);
    }
}

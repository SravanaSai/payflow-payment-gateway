package com.payflow.payment.idempotency;

import com.payflow.payment.config.IdempotencyProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCacheConfiguration.class);

    @Bean
    @Primary
    IdempotencyStore cachingIdempotencyStore(
            JdbcIdempotencyStore postgres,
            StringRedisTemplate redis,
            JsonMapper json,
            Clock clock,
            IdempotencyProperties properties,
            CircuitBreakerRegistry circuitBreakerRegistry,
            MeterRegistry meters) {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(CachingIdempotencyStore.BREAKER);
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "Idempotency cache circuit breaker {}",
                event.getStateTransition()));
        return new CachingIdempotencyStore(
                postgres, redis, json, clock, properties.cache().ttl(), breaker, meters);
    }
}

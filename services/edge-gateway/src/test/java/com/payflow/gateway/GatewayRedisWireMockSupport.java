package com.payflow.gateway;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * WireMock merchant stub plus Redis for gateway integration tests that exercise rate limits.
 */
abstract class GatewayRedisWireMockSupport extends GatewayWireMockSupport {

    @DynamicPropertySource
    static void rateLimitRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestSupport.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> RedisTestSupport.REDIS.getMappedPort(6379));
        registry.add("payflow.gateway.rate-limit.enabled", () -> "true");
    }
}

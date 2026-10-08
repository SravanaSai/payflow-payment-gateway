package com.payflow.payment;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres and real Redis, with the idempotency cache switched on. Deliberately not a
 * subclass of {@link PostgresTestSupport}, whose properties switch the cache off: the order in
 * which inherited dynamic properties override each other is not something to rely on.
 */
public abstract class PostgresRedisTestSupport {

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void postgresAndRedisProperties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerPostgres(registry);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("payflow.idempotency.cache.enabled", () -> "true");
    }
}

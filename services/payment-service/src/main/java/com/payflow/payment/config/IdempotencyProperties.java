package com.payflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** How long idempotency records live, the largest body the filter buffers, and the cache and purge settings. */
@ConfigurationProperties(prefix = "payflow.idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue Cache cache,
        @DefaultValue Purge purge) {

    /** Redis replay cache. An entry never outlives its record in Postgres, whatever this TTL says. */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("10m") Duration ttl) {
    }

    /** Deletion of expired records; the first run happens one interval after startup. */
    public record Purge(
            @DefaultValue("15m") Duration interval,
            @DefaultValue("1000") int batchSize) {
    }
}

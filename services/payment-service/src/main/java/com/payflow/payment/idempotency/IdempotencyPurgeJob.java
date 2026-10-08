package com.payflow.payment.idempotency;

import com.payflow.payment.config.IdempotencyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes expired idempotency records in bounded batches, on the interval configured in
 * {@link IdempotencyProperties}. Several instances running it at once is harmless: deleting
 * an already-deleted row does nothing.
 */
@Component
class IdempotencyPurgeJob implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final JdbcIdempotencyStore store;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;
    private final Counter purged;

    IdempotencyPurgeJob(
            JdbcIdempotencyStore store,
            Clock clock,
            IdempotencyProperties properties,
            MeterRegistry meters) {
        this.store = store;
        this.clock = clock;
        this.interval = properties.purge().interval();
        this.batchSize = properties.purge().batchSize();
        this.purged = meters.counter("payflow.idempotency.purged");
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::purgeExpired, interval, interval));
    }

    int purgeExpired() {
        // Fixed for the whole run, so the loop ends even while new records keep expiring.
        Instant cutoff = clock.instant();
        int total = 0;
        int deleted;
        do {
            deleted = store.deleteExpired(cutoff, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        purged.increment(total);
        if (total > 0) {
            log.info("Purged {} expired idempotency records", total);
        }
        return total;
    }
}

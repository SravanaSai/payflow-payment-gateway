package com.payflow.payment.idempotency;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresRedisTestSupport;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.payflow.payment.idempotency.IdempotencyFilter.KEY_HEADER;
import static com.payflow.payment.idempotency.IdempotencyFilter.REPLAYED_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The whole stack with the cache on: the first retry reads Postgres, every later one is a Redis hit. */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyCacheIntegrationTest extends PostgresRedisTestSupport {

    private static final String CREATE_BODY = "{\"amountMinor\": 5000, \"currency\": \"INR\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private final PaymentOwner owner = new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST);

    @Test
    void breakerUsesConfiguredSettings() {
        var config = circuitBreakerRegistry.circuitBreaker(CachingIdempotencyStore.BREAKER).getCircuitBreakerConfig();
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(5).toMillis());
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void retriesAfterTheFirstAreAnsweredFromRedis() throws Exception {
        String key = UUID.randomUUID().toString();
        double hitsBefore = cacheCount("hit");
        double missesBefore = cacheCount("miss");
        double replaysBefore = requestCount("replayed");

        MvcResult original = create(key);
        MvcResult firstRetry = create(key);
        long ttlMillis = redis.getExpire(
                CachingIdempotencyStore.redisKey(new IdempotencyKey(owner, key)), TimeUnit.MILLISECONDS);
        MvcResult secondRetry = create(key);

        assertThat(original.getResponse().getStatus()).isEqualTo(201);
        assertThat(firstRetry.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        assertThat(secondRetry.getResponse().getContentAsString()).isEqualTo(original.getResponse().getContentAsString());
        assertThat(secondRetry.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
        assertThat(ttlMillis).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(10).toMillis());
        assertThat(cacheCount("miss") - missesBefore).as("the first retry reads Postgres").isEqualTo(1);
        assertThat(cacheCount("hit") - hitsBefore).as("the second retry is served by Redis").isEqualTo(1);
        assertThat(requestCount("replayed") - replaysBefore).isEqualTo(2);
    }

    private MvcResult create(String key) throws Exception {
        return mockMvc.perform(post("/v1/payments")
                        .header(PayFlowHeaders.MERCHANT_ID, owner.merchantId().toString())
                        .header(PayFlowHeaders.ENVIRONMENT, owner.environment().name())
                        .header(KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andReturn();
    }

    private double cacheCount(String result) {
        return meters.counter("payflow.idempotency.cache", "result", result).count();
    }

    private double requestCount(String outcome) {
        return meters.counter("payflow.idempotency.requests", "outcome", outcome).count();
    }
}

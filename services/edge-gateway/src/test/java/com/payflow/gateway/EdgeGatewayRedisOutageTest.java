package com.payflow.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.payflow.gateway.ratelimit.TokenBucketLimiter.BREAKER;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "resilience4j.circuitbreaker.instances.rate-limit-redis.sliding-window-size=4",
                "resilience4j.circuitbreaker.instances.rate-limit-redis.minimum-number-of-calls=4",
                "resilience4j.circuitbreaker.instances.rate-limit-redis.wait-duration-in-open-state=100ms"
        })
@ActiveProfiles("test")
class EdgeGatewayRedisOutageTest extends GatewayWireMockSupport {

    private static final String MERCHANT_ID = "66666666-6666-6666-6666-666666666666";
    private static final String SECRET = "sk_test_redis_outage";
    private static final ServerSocket BLACK_HOLE;
    private static final int BLACK_HOLE_PORT;

    static {
        try {
            BLACK_HOLE = new ServerSocket(0);
            BLACK_HOLE_PORT = BLACK_HOLE.getLocalPort();
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @AfterAll
    static void closeBlackHole() throws IOException {
        BLACK_HOLE.close();
    }

    @DynamicPropertySource
    static void blackHoleRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> BLACK_HOLE_PORT);
        registry.add("payflow.gateway.rate-limit.enabled", () -> "true");
    }

    @LocalServerPort
    int port;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + MERCHANT_ID
                                + "\",\"keyId\":\"ak_test_redis\",\"environment\":\"TEST\"}")));
        PAYMENT.stubFor(get(urlPathEqualTo("/v1/payments/p-redis"))
                .willReturn(aResponse().withStatus(200).withBody("{\"id\":\"p-redis\"}")));
    }

    @Test
    void rateLimitFailsOpenQuicklyWhenRedisIsUnreachable() {
        List<Double> timings = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long start = System.nanoTime();
            ResponseEntity<String> response = getPayment();
            timings.add((System.nanoTime() - start) / 1_000_000.0);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker(BREAKER).getState().name()).isEqualTo("OPEN");
        assertThat(timings.get(7)).isLessThan(100.0);
    }

    private ResponseEntity<String> getPayment() {
        return client.get()
                .uri("/v1/payments/p-redis")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

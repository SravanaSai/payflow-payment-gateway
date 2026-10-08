package com.payflow.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payflow.gateway.rate-limit.enabled=true",
        "payflow.gateway.rate-limit.anonymous.capacity=2",
        "payflow.gateway.rate-limit.anonymous.refill-per-second=0.1"
})
class EdgeGatewayIpRateLimitTest extends GatewayRedisWireMockSupport {

    private static final String MERCHANT_PATH = "/v1/merchants/11111111-1111-1111-1111-111111111111";

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    private RestClient client;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void invalidTokenFloodIsCappedBeforeLookupAmplifies() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errorCode\":\"API_KEY_NOT_FOUND\"}")));

        ResponseEntity<String> first = exchange("GET", MERCHANT_PATH, "bad-1");
        ResponseEntity<String> second = exchange("GET", MERCHANT_PATH, "bad-2");
        ResponseEntity<String> third = exchange("GET", MERCHANT_PATH, "bad-3");

        assertThat(first.getStatusCode().value()).isEqualTo(401);
        assertThat(second.getStatusCode().value()).isEqualTo(401);
        assertThat(third.getStatusCode().value()).isEqualTo(429);

        MERCHANT.verify(2, postRequestedFor(urlPathEqualTo("/internal/v1/api-keys/lookup")));
        MERCHANT.verify(0, getRequestedFor(urlPathEqualTo(MERCHANT_PATH)));
    }

    @Test
    void publicSignupIsCappedByIpLimit() {
        MERCHANT.stubFor(post(urlPathEqualTo("/v1/merchants"))
                .willReturn(aResponse().withStatus(201).withBody("{\"id\":\"new\"}")));

        assertThat(exchange("POST", "/v1/merchants", null).getStatusCode().value()).isEqualTo(201);
        assertThat(exchange("POST", "/v1/merchants", null).getStatusCode().value()).isEqualTo(201);
        assertThat(exchange("POST", "/v1/merchants", null).getStatusCode().value()).isEqualTo(429);

        MERCHANT.verify(2, postRequestedFor(urlPathEqualTo("/v1/merchants")));
    }

    private ResponseEntity<String> exchange(String method, String path, String bearerSecret) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> {
                    if (bearerSecret != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerSecret);
                    }
                    if ("POST".equals(method)) {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    }
                });
        if ("POST".equals(method)) {
            spec = spec.body("{\"name\":\"Rate Limit Co\",\"email\":\"rl@test.com\"}");
        }
        return spec.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

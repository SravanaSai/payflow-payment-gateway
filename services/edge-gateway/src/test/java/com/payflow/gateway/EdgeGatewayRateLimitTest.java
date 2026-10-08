package com.payflow.gateway;

import com.payflow.common.security.HashedSecret;
import com.payflow.common.security.SecretHasher;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.gateway.ratelimit.RateLimitHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payflow.gateway.rate-limit.enabled=true",
        "payflow.gateway.rate-limit.test.capacity=3",
        "payflow.gateway.rate-limit.anonymous.capacity=1000"
})
class EdgeGatewayRateLimitTest extends GatewayRedisWireMockSupport {

    private static final String MERCHANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_MERCHANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String MERCHANT_PATH = "/v1/merchants/" + MERCHANT_ID;
    private static final String OTHER_MERCHANT_PATH = "/v1/merchants/" + OTHER_MERCHANT_ID;
    private static final String SECRET = "sk_test_rate_limit";
    private static final String OTHER_SECRET = "sk_test_other_merchant";
    private static final String LIVE_SECRET = "sk_live_rate_limit";

    @LocalServerPort
    int port;

    @Autowired
    SecretHasher secretHasher;

    @Autowired
    StringRedisTemplate redis;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        stubLookup(MERCHANT_ID, SECRET, "TEST");
        stubLookup(MERCHANT_ID, LIVE_SECRET, "LIVE");
        stubLookup(OTHER_MERCHANT_ID, OTHER_SECRET, "TEST");
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        MERCHANT.stubFor(get(urlPathEqualTo(OTHER_MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
    }

    @Test
    void merchantBucketReturns429WhenExceeded() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> ok = exchange("GET", MERCHANT_PATH, SECRET);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            assertThat(ok.getHeaders().getFirst(RateLimitHeaders.LIMIT)).isEqualTo("3");
        }

        ResponseEntity<String> limited = exchange("GET", MERCHANT_PATH, SECRET);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getBody()).contains("RATE_LIMITED");
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(limited.getHeaders().getFirst(RateLimitHeaders.REMAINING)).isEqualTo("0");
    }

    @Test
    void merchantsHaveIndependentBuckets() {
        for (int i = 0; i < 3; i++) {
            assertThat(exchange("GET", MERCHANT_PATH, SECRET).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(exchange("GET", MERCHANT_PATH, SECRET).getStatusCode().value()).isEqualTo(429);

        assertThat(exchange("GET", OTHER_MERCHANT_PATH, OTHER_SECRET).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void testAndLiveBucketsAreIndependent() {
        for (int i = 0; i < 3; i++) {
            assertThat(exchange("GET", MERCHANT_PATH, SECRET).getStatusCode().value()).isEqualTo(200);
        }
        assertThat(exchange("GET", MERCHANT_PATH, SECRET).getStatusCode().value()).isEqualTo(429);

        assertThat(exchange("GET", MERCHANT_PATH, LIVE_SECRET).getStatusCode().value()).isEqualTo(200);
    }

    private void stubLookup(String merchantId, String secret, String environment) {
        HashedSecret hash = secretHasher.hash(secret);
        String lookupJson = "{\"candidates\":[{\"pepperVersion\":" + hash.pepperVersion()
                + ",\"secretHash\":\"" + hash.toBase64() + "\"}]}";
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .withRequestBody(equalTo(lookupJson))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + merchantId + "\",\"keyId\":\"ak_test_rl\",\"environment\":\""
                                + environment + "\"}")));
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

package com.payflow.gateway;

import com.payflow.common.web.PayFlowHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client on the public sign-up route pretends to be another merchant. Without the scrub,
 * the forged headers were forwarded and spent the victim's rate-limit quota.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "payflow.gateway.rate-limit.enabled=true",
        "payflow.gateway.rate-limit.anonymous.capacity=1000"
})
class EdgeGatewayHeaderSpoofingTest extends GatewayRedisWireMockSupport {

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    private RestClient client;

    private final String victimId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        MERCHANT.stubFor(post(urlPathEqualTo("/v1/merchants"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":\"new-merchant\"}")));
    }

    @Test
    void forgedIdentityOnSignUpIsNeverForwarded() {
        assertThat(signUpPretendingToBe(victimId).getStatusCode().value()).isEqualTo(201);

        MERCHANT.verify(postRequestedFor(urlPathEqualTo("/v1/merchants"))
                .withoutHeader(PayFlowHeaders.MERCHANT_ID)
                .withoutHeader(PayFlowHeaders.ENVIRONMENT)
                .withoutHeader(PayFlowHeaders.KEY_ID));
    }

    @Test
    void forgedIdentityCannotSpendAnotherMerchantsQuota() {
        for (int i = 0; i < 5; i++) {
            signUpPretendingToBe(victimId);
        }

        assertThat(redis.hasKey("rl:mkt:" + victimId + ":LIVE")).isFalse();
    }

    private ResponseEntity<String> signUpPretendingToBe(String merchantId) {
        return client.post()
                .uri("/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .header(PayFlowHeaders.MERCHANT_ID, merchantId)
                .header(PayFlowHeaders.ENVIRONMENT, "LIVE")
                .header(PayFlowHeaders.KEY_ID, "ak_live_forged")
                .body("{\"name\":\"Mallory\",\"email\":\"mallory@example.test\"}")
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

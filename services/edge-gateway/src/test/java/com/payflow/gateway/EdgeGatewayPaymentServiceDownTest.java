package com.payflow.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeGatewayPaymentServiceDownTest {

    private static final WireMockServer MERCHANT = new WireMockServer(wireMockConfig().dynamicPort());
    private static final int DEAD_PORT;

    static {
        MERCHANT.start();
        try (ServerSocket socket = new ServerSocket(0)) {
            DEAD_PORT = socket.getLocalPort();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final String MERCHANT_ID = "55555555-5555-5555-5555-555555555555";
    private static final String SECRET = "sk_test_dead_port";
    private static final String CREATE_BODY = "{\"amountMinor\":5000,\"currency\":\"INR\"}";

    @DynamicPropertySource
    static void uris(DynamicPropertyRegistry registry) {
        registry.add("payflow.gateway.merchant-uri", MERCHANT::baseUrl);
        registry.add("payflow.gateway.payment-uri", () -> "http://127.0.0.1:" + DEAD_PORT);
    }

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void resetAndClient() {
        MERCHANT.resetAll();
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void unreachablePaymentServiceIs503() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + MERCHANT_ID
                                + "\",\"keyId\":\"ak_test_dead\",\"environment\":\"TEST\"}")));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
    }

    private static Consumer<HttpHeaders> bearer(String secret) {
        return headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
    }

    private ResponseEntity<String> postPayment(String path, Consumer<HttpHeaders> headers) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .body(CREATE_BODY)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

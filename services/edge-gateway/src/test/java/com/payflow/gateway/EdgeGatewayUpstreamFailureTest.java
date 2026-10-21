package com.payflow.gateway;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=300ms")
@ActiveProfiles("test")
class EdgeGatewayUpstreamFailureTest extends GatewayWireMockSupport {

    private static final String MERCHANT_ID = "44444444-4444-4444-4444-444444444444";
    private static final String SECRET = "sk_test_upstream";
    private static final String CREATE_BODY = "{\"amountMinor\":5000,\"currency\":\"INR\"}";

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void slowPaymentServiceIs504UpstreamTimeout() {
        stubLookup();
        PAYMENT.stubFor(post(urlPathEqualTo("/v1/payments"))
                .willReturn(aResponse().withFixedDelay(1000).withStatus(201).withBody("{}")));

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody()).contains("UPSTREAM_TIMEOUT");
        assertThat(elapsed).isLessThan(2000);
    }

    @Test
    void connectionResetIs503() {
        stubLookup();
        PAYMENT.stubFor(post(urlPathEqualTo("/v1/payments"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
    }

    @Test
    void processorErrorPassesThroughAs502() {
        stubLookup();
        PAYMENT.stubFor(post(urlPathEqualTo("/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .withBody("{\"errorCode\":\"PROCESSOR_ERROR\",\"detail\":\"bad psp\"}")));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).contains("PROCESSOR_ERROR");
    }

    @Test
    void serviceUnavailablePassesThroughWithReplayHeader() {
        stubLookup();
        PAYMENT.stubFor(post(urlPathEqualTo("/v1/payments"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Idempotency-Replayed", "true")
                        .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .withBody("{\"errorCode\":\"SERVICE_UNAVAILABLE\"}")));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    private void stubLookup() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + MERCHANT_ID
                                + "\",\"keyId\":\"ak_test_upstream\",\"environment\":\"TEST\"}")));
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

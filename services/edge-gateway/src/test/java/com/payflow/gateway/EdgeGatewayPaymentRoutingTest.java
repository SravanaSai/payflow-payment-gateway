package com.payflow.gateway;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.payflow.common.web.CorrelationId;
import com.payflow.common.web.PayFlowHeaders;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** The gateway's half of a payment: authenticate, stamp identity, proxy to payment-service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeGatewayPaymentRoutingTest extends GatewayWireMockSupport {

    private static final String MERCHANT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String SECRET = "sk_test_payments";
    private static final String CREATE_BODY = "{\"amountMinor\":5000,\"currency\":\"INR\"}";

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void aPaymentWithoutAKeyIsRejectedAndNeverReachesPaymentService() {
        ResponseEntity<String> response = postPayment("/v1/payments", headers -> { });

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
        assertThat(PAYMENT.getAllServeEvents()).isEmpty();
    }

    @Test
    void anUnknownKeyIsRejected() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse().withStatus(404)));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer("sk_test_unknown"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(PAYMENT.getAllServeEvents()).isEmpty();
    }

    @Test
    void aValidKeyReachesPaymentServiceAsIdentityNeverAsTheSecret() {
        stubLookup();
        stubPaymentService("/v1/payments", aResponse().withStatus(201)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"id\":\"p-1\",\"status\":\"PENDING\"}"));
        String idempotencyKey = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET).andThen(headers -> {
            headers.set("Idempotency-Key", idempotencyKey);
            headers.set(CorrelationId.HEADER, correlationId);
            headers.set(PayFlowHeaders.MERCHANT_ID, UUID.randomUUID().toString());
        }));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).contains("PENDING");
        PAYMENT.verify(postRequestedFor(urlPathEqualTo("/v1/payments"))
                .withHeader(PayFlowHeaders.MERCHANT_ID, equalTo(MERCHANT_ID))
                .withHeader(PayFlowHeaders.ENVIRONMENT, equalTo("TEST"))
                .withHeader(PayFlowHeaders.KEY_ID, equalTo("ak_test_payments"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey))
                .withHeader(CorrelationId.HEADER, equalTo(correlationId))
                .withoutHeader(HttpHeaders.AUTHORIZATION)
                .withRequestBody(equalToJson(CREATE_BODY)));
    }

    @Test
    void commandsOnAPaymentAreRoutedToo() {
        stubLookup();
        stubPaymentService("/v1/payments/p-1/capture", aResponse().withStatus(200).withBody("{\"status\":\"CAPTURED\"}"));

        ResponseEntity<String> response = postPayment("/v1/payments/p-1/capture", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        PAYMENT.verify(postRequestedFor(urlPathEqualTo("/v1/payments/p-1/capture")));
    }

    @Test
    void theReplayHeaderComesBackToTheClient() {
        stubLookup();
        stubPaymentService("/v1/payments", aResponse().withStatus(201)
                .withHeader("Idempotency-Replayed", "true")
                .withBody("{\"id\":\"p-1\"}"));

        ResponseEntity<String> response = postPayment("/v1/payments", bearer(SECRET));

        assertThat(response.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
    }

    @Test
    void paymentServiceErrorsPassThroughUnchanged() {
        stubLookup();
        stubPaymentService("/v1/payments/p-1/capture", aResponse().withStatus(422)
                .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withBody("{\"errorCode\":\"INVALID_STATE_TRANSITION\"}"));

        ResponseEntity<String> response = postPayment("/v1/payments/p-1/capture", bearer(SECRET));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("INVALID_STATE_TRANSITION");
    }

    private void stubLookup() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + MERCHANT_ID
                                + "\",\"keyId\":\"ak_test_payments\",\"environment\":\"TEST\"}")));
    }

    private static void stubPaymentService(String path, ResponseDefinitionBuilder response) {
        PAYMENT.stubFor(post(urlPathEqualTo(path)).willReturn(response));
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

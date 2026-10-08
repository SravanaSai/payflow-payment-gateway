package com.payflow.gateway;

import com.payflow.common.web.CorrelationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeGatewayRoutingTest extends GatewayWireMockSupport {

    private static final String MERCHANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String MERCHANT_PATH = "/v1/merchants/" + MERCHANT_ID;
    private static final String BEARER_SECRET = "sk_test_routing";

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    @Test
    void allowlistedPathIsProxiedAndCorrelationIdIsForwarded() {
        stubSuccessfulLookup();
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":\"" + MERCHANT_ID + "\",\"name\":\"Acme\"}")));

        String supplied = UUID.randomUUID().toString();
        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, supplied, BEARER_SECRET, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Acme");
        assertThat(response.getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo(supplied);

        MERCHANT.verify(getRequestedFor(urlPathEqualTo(MERCHANT_PATH))
                .withHeader(CorrelationId.HEADER, equalTo(supplied)));
    }

    @Test
    void generatesCorrelationIdWhenClientOmitsItAndForwardsTheSameValue() {
        stubSuccessfulLookup();
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, null, BEARER_SECRET, null);
        String echoed = response.getHeaders().getFirst(CorrelationId.HEADER);

        assertThat(echoed).isNotBlank();
        MERCHANT.verify(getRequestedFor(urlPathEqualTo(MERCHANT_PATH))
                .withHeader(CorrelationId.HEADER, equalTo(echoed)));
    }

    @Test
    void internalPathsAreNotRouted() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse().withStatus(200).withBody("should-not-be-reached")));

        ResponseEntity<String> response = exchange("POST", "/internal/v1/api-keys/lookup", null, null, "{}");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        MERCHANT.verify(0, postRequestedFor(urlEqualTo("/internal/v1/api-keys/lookup")));
    }

    @Test
    void anonymousCallsToAnyV1PathAreRejectedBeforeRouting() {
        ResponseEntity<String> response = exchange("GET", "/v1/refunds", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(MERCHANT.getAllServeEvents()).isEmpty();
        assertThat(PAYMENT.getAllServeEvents()).isEmpty();
    }

    @Test
    void authenticatedCallsToAnUnroutedPathAreNotFound() {
        stubSuccessfulLookup();

        ResponseEntity<String> response = exchange("GET", "/v1/refunds", null, BEARER_SECRET, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        MERCHANT.verify(0, getRequestedFor(urlEqualTo("/v1/refunds")));
        assertThat(PAYMENT.getAllServeEvents()).isEmpty();
    }

    @Test
    void downstreamClientErrorIsPassedThrough() {
        stubSuccessfulLookup();
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"errorCode\":\"MERCHANT_NOT_FOUND\"}")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, null, BEARER_SECRET, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("MERCHANT_NOT_FOUND");
    }

    @Test
    void downstreamServerErrorIsPassedThrough() {
        stubSuccessfulLookup();
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, null, BEARER_SECRET, null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("upstream down");
    }

    @Test
    void actuatorHealthIsServedByTheGateway() {
        ResponseEntity<String> response = exchange("GET", "/actuator/health", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
        MERCHANT.verify(0, getRequestedFor(urlEqualTo("/actuator/health")));
    }

    @Test
    void createMerchantIsPublicAndDoesNotCallLookup() {
        MERCHANT.stubFor(post(urlPathEqualTo("/v1/merchants"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"id\":\"" + MERCHANT_ID + "\",\"apiKey\":{\"secret\":\"sk_test_once\"}}")));

        ResponseEntity<String> response = exchange(
                "POST", "/v1/merchants", null, null, "{\"name\":\"Acme\",\"email\":\"ops@acme.com\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).contains("sk_test_once");
        MERCHANT.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/api-keys/lookup")));
    }

    private void stubSuccessfulLookup() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"merchantId\":\"" + MERCHANT_ID
                                + "\",\"keyId\":\"ak_test_routing\",\"environment\":\"TEST\"}")));
    }

    private ResponseEntity<String> exchange(
            String method, String path, String correlationId, String bearerSecret, String body) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> {
                    if (correlationId != null) {
                        headers.set(CorrelationId.HEADER, correlationId);
                    }
                    if (bearerSecret != null) {
                        headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + bearerSecret);
                    }
                    if (body != null) {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    }
                });
        if (body != null) {
            spec.body(body);
        }
        return spec.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

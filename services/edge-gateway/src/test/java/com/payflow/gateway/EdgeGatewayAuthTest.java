package com.payflow.gateway;

import com.payflow.common.security.HashedSecret;
import com.payflow.common.security.SecretHasher;
import com.payflow.common.web.PayFlowHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EdgeGatewayAuthTest extends GatewayWireMockSupport {

    private static final String MERCHANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_MERCHANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String MERCHANT_PATH = "/v1/merchants/" + MERCHANT_ID;
    private static final String SECRET = "sk_test_auth_secret";

    @LocalServerPort
    int port;

    @Autowired
    SecretHasher secretHasher;

    private RestClient client;

    @BeforeEach
    void setUpClient() {
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @Test
    void missingBearerReturns401AndDoesNotProxy() {
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("should-not-be-reached")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        MERCHANT.verify(0, getRequestedFor(urlPathEqualTo(MERCHANT_PATH)));
        MERCHANT.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/api-keys/lookup")));
    }

    @Test
    void unknownKeyReturns401() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errorCode\":\"API_KEY_NOT_FOUND\"}")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("UNAUTHORIZED");
        MERCHANT.verify(0, getRequestedFor(urlPathEqualTo(MERCHANT_PATH)));
    }

    @Test
    void validKeyIsLookedUpByHashAndIdentityIsStampedWithoutAuthorization() {
        HashedSecret hash = secretHasher.hash(SECRET);
        String lookupJson = "{\"candidates\":[{\"pepperVersion\":" + hash.pepperVersion()
                + ",\"secretHash\":\"" + hash.toBase64() + "\"}]}";
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .withRequestBody(equalToJson(lookupJson))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(lookupBody(MERCHANT_ID))));
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, SECRET, "forged-merchant");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        MERCHANT.verify(getRequestedFor(urlPathEqualTo(MERCHANT_PATH))
                .withHeader(PayFlowHeaders.MERCHANT_ID, equalTo(MERCHANT_ID))
                .withHeader(PayFlowHeaders.KEY_ID, equalTo("ak_test_auth"))
                .withHeader(PayFlowHeaders.ENVIRONMENT, equalTo("TEST"))
                .withHeader(HttpHeaders.AUTHORIZATION, absent()));
    }

    @Test
    void keyForAnotherMerchantIsForbidden() {
        MERCHANT.stubFor(post(urlPathEqualTo("/internal/v1/api-keys/lookup"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(lookupBody(OTHER_MERCHANT_ID))));
        MERCHANT.stubFor(get(urlPathEqualTo(MERCHANT_PATH))
                .willReturn(aResponse().withStatus(200).withBody("should-not-be-reached")));

        ResponseEntity<String> response = exchange("GET", MERCHANT_PATH, SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("FORBIDDEN");
        MERCHANT.verify(0, getRequestedFor(urlPathEqualTo(MERCHANT_PATH)));
    }

    private static String lookupBody(String merchantId) {
        return "{\"merchantId\":\"" + merchantId + "\",\"keyId\":\"ak_test_auth\",\"environment\":\"TEST\"}";
    }

    private ResponseEntity<String> exchange(String method, String path, String bearerSecret) {
        return exchange(method, path, bearerSecret, null);
    }

    private ResponseEntity<String> exchange(
            String method, String path, String bearerSecret, String spoofedMerchantId) {
        RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .headers(headers -> {
                    if (bearerSecret != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerSecret);
                    }
                    if (spoofedMerchantId != null) {
                        headers.set(PayFlowHeaders.MERCHANT_ID, spoofedMerchantId);
                    }
                });
        return spec.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }
}

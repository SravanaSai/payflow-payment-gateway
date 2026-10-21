package com.payflow.payment.adapter.out.processor.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.jayway.jsonpath.JsonPath;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresTestSupport;
import com.payflow.payment.application.port.out.PaymentProcessor;
import com.payflow.payment.config.ProcessorProperties;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HttpProcessorIntegrationTest extends PostgresTestSupport {

    private static final WireMockServer PSP = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PSP.start();
    }

    @DynamicPropertySource
    static void httpProcessor(DynamicPropertyRegistry registry) {
        registry.add("payflow.processor.type", () -> "http");
        registry.add("payflow.processor.http.base-url", PSP::baseUrl);
        registry.add("payflow.processor.http.api-key", () -> "test_key");
        registry.add("resilience4j.retry.instances.psp.wait-duration", () -> "10ms");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentProcessor paymentProcessor;

    @Autowired
    private ProcessorProperties processorProperties;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String merchantId = UUID.randomUUID().toString();

    @BeforeEach
    void resetPsp() {
        PSP.resetAll();
        circuitBreakerRegistry.circuitBreaker(PspResilience.INSTANCE).reset();
    }

    @Test
    void httpProcessorIsActive() {
        assertThat(paymentProcessor).isInstanceOf(HttpPaymentProcessor.class);
    }

    @Test
    void yamlResilienceSettingsAreApplied() {
        Retry retry = retryRegistry.retry(PspResilience.INSTANCE);
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker(PspResilience.INSTANCE);
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(bulkheadRegistry.bulkhead(PspResilience.INSTANCE).getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(20);
    }

    @Test
    void processorTimeoutBudgetStaysUnderGatewayLimit() {
        ProcessorProperties.Http http = processorProperties.http();
        Retry retry = retryRegistry.retry(PspResilience.INSTANCE);
        int attempts = retry.getRetryConfig().getMaxAttempts();
        long perAttemptMs = http.connectTimeout().toMillis() + http.readTimeout().toMillis();
        long backoffMs = 450;
        assertThat(attempts * perAttemptMs + backoffMs).isLessThan(4500);
    }

    @Test
    void authorizeThroughApiStoresProcessorReference() throws Exception {
        String paymentId = createPayment(5_000);
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"auth_wire","status":"approved"}
                                """)));

        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(command("auth-http-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processorReference").value("auth_wire"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void declineThroughApiIsStored() throws Exception {
        String paymentId = createPayment(5_000);
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error","code":"card_declined","decline_code":"do_not_honor","message":"No"}}
                                """)));

        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(command("decline-http")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_DECLINED"));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void outageThenRetryWithSameMerchantKey() throws Exception {
        String paymentId = createPayment(5_000);
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse().withStatus(503)));

        HttpHeaders headers = command("outage-key");
        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(headers))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        PSP.resetAll();
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"auth_after_outage\",\"status\":\"approved\"}")));

        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(headers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void brokenProcessorAnswerIs502AndLeavesPaymentPending() throws Exception {
        String paymentId = createPayment(5_000);
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"authentication_error","code":"invalid_api_key","message":"bad key"}}
                                """)));

        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(command("bad-psp")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("PROCESSOR_ERROR"));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void captureThroughApiRecordsCapturedAmount() throws Exception {
        String paymentId = authorizedPayment("auth_cap_ok");
        stubCapture("auth_cap_ok", aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"cap_1","authorization_id":"auth_cap_ok","status":"succeeded"}
                        """));

        mockMvc.perform(capture(paymentId, "capture-ok", 3_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(3_000))
                .andExpect(jsonPath("$.processorReference").value("auth_cap_ok"));
    }

    @Test
    void declinedCaptureIs422AndLeavesPaymentAuthorized() throws Exception {
        String paymentId = authorizedPayment("auth_cap_decline");
        stubCapture("auth_cap_decline", aResponse()
                .withStatus(402)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"type":"card_error","code":"card_declined","decline_code":"do_not_honor","message":"No"}}
                        """));

        mockMvc.perform(capture(paymentId, "capture-decline", 3_000))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_DECLINED"));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(0));
    }

    @Test
    void captureAfterDeclineSucceedsWithANewProcessorKey() throws Exception {
        String paymentId = authorizedPayment("auth_cap_retry", 150_000);
        String firstKey = paymentId + ":capture";
        String secondKey = paymentId + ":capture:1";

        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/v1/authorizations/auth_cap_retry/captures"))
                .withHeader("Idempotency-Key", equalTo(firstKey))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error","code":"card_declined","decline_code":"do_not_honor","message":"No"}}
                                """)));
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/v1/authorizations/auth_cap_retry/captures"))
                .withHeader("Idempotency-Key", equalTo(secondKey))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"cap_retry","authorization_id":"auth_cap_retry","status":"succeeded"}
                                """)));

        mockMvc.perform(capture(paymentId, "capture-decline-retry", 100_051))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(capture(paymentId, "capture-decline-retry-2", 100_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT capture_attempts FROM payments WHERE id = ?", Integer.class, UUID.fromString(paymentId));
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    void captureOutageIs503ThenSameMerchantKeyRetrySucceeds() throws Exception {
        String paymentId = authorizedPayment("auth_cap_outage");
        stubCapture("auth_cap_outage", aResponse().withStatus(503));

        mockMvc.perform(capture(paymentId, "capture-outage", 3_000))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        PSP.resetAll();
        stubCapture("auth_cap_outage", aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"cap_2","authorization_id":"auth_cap_outage","status":"succeeded"}
                        """));

        mockMvc.perform(capture(paymentId, "capture-outage", 3_000))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void captureOfAHoldTheProcessorForgotIs502AndLeavesPaymentAuthorized() throws Exception {
        String paymentId = authorizedPayment("auth_cap_gone");
        stubCapture("auth_cap_gone", aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"type":"invalid_request_error","code":"resource_missing","message":"No such authorization"}}
                        """));

        mockMvc.perform(capture(paymentId, "capture-gone", 3_000))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("PROCESSOR_ERROR"));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void captureOfPaymentWithoutProcessorReferenceIs502WithoutCallingProcessor() throws Exception {
        String paymentId = authorizedPayment("auth_cap_legacy");
        jdbcTemplate.update("UPDATE payments SET processor_reference = NULL WHERE id = ?", UUID.fromString(paymentId));
        PSP.resetAll();

        mockMvc.perform(capture(paymentId, "capture-legacy", 3_000))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("PROCESSOR_ERROR"));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).headers(readHeaders()))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        assertThat(PSP.getAllServeEvents()).isEmpty();
    }

    private String authorizedPayment(String processorReference) throws Exception {
        return authorizedPayment(processorReference, 5_000);
    }

    private String authorizedPayment(String processorReference, long amountMinor) throws Exception {
        String paymentId = createPayment(amountMinor);
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"%s\",\"status\":\"approved\"}".formatted(processorReference))));
        mockMvc.perform(post("/v1/payments/{id}/authorize", paymentId).headers(command("authorize-" + processorReference)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        return paymentId;
    }

    private void stubCapture(String processorReference, ResponseDefinitionBuilder response) {
        PSP.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathEqualTo("/v1/authorizations/" + processorReference + "/captures"))
                .willReturn(response));
    }

    private MockHttpServletRequestBuilder capture(String paymentId, String idempotencyKey, long amountMinor) {
        return post("/v1/payments/{id}/capture", paymentId)
                .headers(command(idempotencyKey))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": " + amountMinor + "}");
    }

    private String createPayment(long amountMinor) throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/payments").headers(command("create-" + amountMinor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": " + amountMinor + ", \"currency\": \"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private HttpHeaders command(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, merchantId);
        headers.set(PayFlowHeaders.ENVIRONMENT, "TEST");
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private HttpHeaders readHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, merchantId);
        headers.set(PayFlowHeaders.ENVIRONMENT, "TEST");
        return headers;
    }
}

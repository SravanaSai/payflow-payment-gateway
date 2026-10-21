package com.payflow.payment.adapter.out.processor.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.payflow.common.core.domain.Currency;
import com.payflow.common.core.domain.Money;
import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.common.web.CorrelationId;
import org.slf4j.MDC;
import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.ProcessorResult;
import com.payflow.payment.config.ProcessorProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpPaymentProcessorTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private PspClient client;
    private HttpPaymentProcessor processor;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        ProcessorProperties.Http http = new ProcessorProperties.Http(
                URI.create(WIRE_MOCK.baseUrl()),
                "test_key",
                Duration.ofMillis(200),
                Duration.ofMillis(300));
        client = PspClient.create(RestClient.builder(), http, json);
        processor = newProcessor(defaultBulkhead(), defaultRetry(), defaultBreaker());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    void forwardsCorrelationIdToProcessor() {
        PaymentId id = PaymentId.generate();
        String correlationId = "corr-" + id;
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                    .withHeader(CorrelationId.HEADER, equalTo(correlationId))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"auth_corr\",\"status\":\"approved\"}")));

            processor.authorize(authorizeRequest(id));

            WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/v1/authorizations"))
                    .withHeader(CorrelationId.HEADER, equalTo(correlationId)));
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    @Test
    void omitsCorrelationIdWhenAbsent() {
        PaymentId id = PaymentId.generate();
        MDC.remove(CorrelationId.MDC_KEY);
        stubAuthorize201("auth_no_corr", id);

        processor.authorize(authorizeRequest(id));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/v1/authorizations"))
                .withoutHeader(CorrelationId.HEADER));
    }

    @Test
    void authorizeSendsSnakeCaseBodyBearerKeyAndIdempotencyKey() {
        PaymentId id = PaymentId.generate();
        AuthorizationRequest request = authorizeRequest(id);

        stubAuthorize201("auth_1", id);

        ProcessorResult result = processor.authorize(request);

        assertThat(result).isEqualTo(new ProcessorResult.Approved("auth_1"));
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/v1/authorizations"))
                .withHeader("Authorization", equalTo("Bearer test_key"))
                .withHeader("Idempotency-Key", equalTo(id + ":authorize"))
                .withRequestBody(equalToJson("""
                        {"amount_minor":15000,"currency":"INR","merchant_reference":"%s"}
                        """.formatted(id))));
    }

    @Test
    void captureTargetsHoldAndReturnsStableReference() {
        PaymentId id = PaymentId.generate();
        CaptureRequest request = captureRequest(id, "auth_1", 10_000);

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"cap_1","authorization_id":"auth_1","status":"succeeded"}
                                """)));

        ProcessorResult result = processor.capture(request);

        assertThat(result).isEqualTo(new ProcessorResult.Approved("auth_1"));
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .withHeader("Idempotency-Key", equalTo(id + ":capture")));
    }

    @Test
    void captureDeclineIsNotRetried() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds","message":"No funds"}}
                                """)));

        ProcessorResult result = processor.capture(captureRequest(id, "auth_1", 10_000));

        assertThat(result).isEqualTo(new ProcessorResult.Declined("insufficient_funds", "No funds"));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/authorizations/auth_1/captures")));
    }

    @Test
    void captureOutageRetriesWithSameKeyThenReturnsUnavailable() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .willReturn(aResponse().withStatus(503)));

        ProcessorResult result = processor.capture(captureRequest(id, "auth_1", 10_000));

        assertThat(result).isInstanceOf(ProcessorResult.Unavailable.class);
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .withHeader("Idempotency-Key", equalTo(id + ":capture"))
                .withRequestBody(equalToJson("{\"amount_minor\":10000}")));
    }

    @Test
    void captureOfAHoldTheProcessorForgotIs502AfterOneCall() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations/auth_gone/captures"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"invalid_request_error","code":"resource_missing","message":"No such authorization"}}
                                """)));

        assertThatThrownBy(() -> processor.capture(captureRequest(id, "auth_gone", 10_000)))
                .isInstanceOf(PayFlowException.class)
                .extracting(ex -> ((PayFlowException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROCESSOR_ERROR);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/authorizations/auth_gone/captures")));
    }

    @Test
    void captureKeyReusedForADifferentAmountIs502AfterOneCall() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations/auth_1/captures"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"idempotency_error","code":"idempotency_key_reused","message":"Key reused"}}
                                """)));

        assertThatThrownBy(() -> processor.capture(captureRequest(id, "auth_1", 9_000)))
                .isInstanceOf(PayFlowException.class)
                .extracting(ex -> ((PayFlowException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROCESSOR_ERROR);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/authorizations/auth_1/captures")));
    }

    @Test
    void unknownResponseFieldsAreIgnored() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"auth_x","status":"approved","extra_field":"ignored"}
                                """)));

        ProcessorResult result = processor.authorize(authorizeRequest(id));

        assertThat(result).isEqualTo(new ProcessorResult.Approved("auth_x"));
    }

    @Test
    void declineIsNotRetried() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds","message":"No funds"}}
                                """)));

        ProcessorResult result = processor.authorize(authorizeRequest(id));

        assertThat(result).isInstanceOf(ProcessorResult.Declined.class);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/authorizations")));
    }

    @Test
    void retries503ThenApprovesWithSameIdempotencyKey() {
        PaymentId id = PaymentId.generate();
        AuthorizationRequest request = authorizeRequest(id);

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .inScenario("flaky")
                .whenScenarioStateIs("Started")
                .willSetStateTo("Second")
                .willReturn(aResponse().withStatus(503)));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .inScenario("flaky")
                .whenScenarioStateIs("Second")
                .willSetStateTo("Third")
                .willReturn(aResponse().withStatus(503)));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .inScenario("flaky")
                .whenScenarioStateIs("Third")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"auth_ok\",\"status\":\"approved\"}")));

        ProcessorResult result = processor.authorize(request);

        assertThat(result).isEqualTo(new ProcessorResult.Approved("auth_ok"));
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/authorizations"))
                .withHeader("Idempotency-Key", equalTo(id + ":authorize")));
    }

    @Test
    void exhausted503ReturnsUnavailable() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse().withStatus(503)));

        ProcessorResult result = processor.authorize(authorizeRequest(id));

        assertThat(result).isInstanceOf(ProcessorResult.Unavailable.class);
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/authorizations")));
    }

    @Test
    void connectionResetIsRetried() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .inScenario("reset")
                .whenScenarioStateIs("Started")
                .willSetStateTo("Ok")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .inScenario("reset")
                .whenScenarioStateIs("Ok")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"auth_ok\",\"status\":\"approved\"}")));

        ProcessorResult result = processor.authorize(authorizeRequest(id));

        assertThat(result).isEqualTo(new ProcessorResult.Approved("auth_ok"));
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo("/v1/authorizations")));
    }

    @Test
    void readTimeoutOnEveryAttemptReturnsUnavailableQuickly() {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withFixedDelay(400)
                        .withBody("{\"id\":\"auth_slow\",\"status\":\"approved\"}")));

        long start = System.nanoTime();
        ProcessorResult result = processor.authorize(authorizeRequest(id));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isInstanceOf(ProcessorResult.Unavailable.class);
        assertThat(((ProcessorResult.Unavailable) result).reason())
                .contains("did not respond in time");
        assertThat(elapsedMs).isLessThan(1500);
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo("/v1/authorizations")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404})
    void integrationErrorsAre502AfterOneCall(int status) {
        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"type\":\"x\",\"code\":\"y\",\"message\":\"z\"}}")));

        CircuitBreaker breaker = defaultBreaker();
        HttpPaymentProcessor local = newProcessor(defaultBulkhead(), defaultRetry(), breaker);

        assertThatThrownBy(() -> local.authorize(authorizeRequest(id)))
                .isInstanceOf(PayFlowException.class)
                .extracting(ex -> ((PayFlowException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROCESSOR_ERROR);
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/v1/authorizations")));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void captureWithoutProcessorReferenceFailsBeforeHttp() {
        PaymentId id = PaymentId.generate();
        CaptureRequest request = new CaptureRequest(id, null, Money.ofMinorUnits(100, Currency.INR), id + ":capture");

        assertThatThrownBy(() -> processor.capture(request))
                .isInstanceOf(PayFlowException.class)
                .extracting(ex -> ((PayFlowException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PROCESSOR_ERROR);
        assertThat(WIRE_MOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void circuitBreakerOpensAndRecovers() throws Exception {
        CircuitBreakerConfig breakerConfig = PspResilience.classifyBreaker(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(250))
                .permittedNumberOfCallsInHalfOpenState(1)).build();
        CircuitBreaker breaker = CircuitBreaker.of("test", breakerConfig);
        HttpPaymentProcessor local = newProcessor(defaultBulkhead(), defaultRetry(), breaker);

        PaymentId id = PaymentId.generate();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse().withStatus(503)));

        for (int i = 0; i < 4; i++) {
            local.authorize(authorizeRequest(PaymentId.generate()));
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        WIRE_MOCK.resetRequests();
        ProcessorResult fastFail = local.authorize(authorizeRequest(id));
        assertThat(fastFail).isInstanceOf(ProcessorResult.Unavailable.class);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo("/v1/authorizations")));

        Thread.sleep(300);
        stubAuthorize201("auth_recovered", id);
        ProcessorResult recovered = local.authorize(authorizeRequest(id));
        assertThat(recovered).isEqualTo(new ProcessorResult.Approved("auth_recovered"));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void declinesDoNotTripCircuitBreaker() {
        CircuitBreaker breaker = defaultBreaker();
        HttpPaymentProcessor local = newProcessor(defaultBulkhead(), defaultRetry(), breaker);

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error","code":"card_declined","decline_code":"do_not_honor","message":"No"}}
                                """)));

        for (int i = 0; i < 10; i++) {
            local.authorize(authorizeRequest(PaymentId.generate()));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void bulkheadRejectsWhenFull() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        HttpPaymentProcessor local = newProcessor(bulkhead, defaultRetry(), defaultBreaker());

        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withFixedDelay(250)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"auth_slow\",\"status\":\"approved\"}")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessorResult> first = executor.submit(
                    () -> local.authorize(authorizeRequest(PaymentId.generate())));
            Thread.sleep(50);
            long start = System.nanoTime();
            ProcessorResult second = local.authorize(authorizeRequest(PaymentId.generate()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(second).isInstanceOf(ProcessorResult.Unavailable.class);
            assertThat(((ProcessorResult.Unavailable) second).reason()).contains("busy");
            assertThat(elapsedMs).isLessThan(100);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(new ProcessorResult.Approved("auth_slow"));
        } finally {
            executor.shutdownNow();
        }
    }

    private HttpPaymentProcessor newProcessor(Bulkhead bulkhead, Retry retry, CircuitBreaker breaker) {
        return new HttpPaymentProcessor(client, bulkhead, retry, breaker);
    }

    private static Retry defaultRetry() {
        RetryConfig config = PspResilience.classifyRetry(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))).build();
        return Retry.of("test", config);
    }

    private static Bulkhead defaultBulkhead() {
        return Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(20).maxWaitDuration(Duration.ZERO).build());
    }

    private static CircuitBreaker defaultBreaker() {
        CircuitBreakerConfig config = PspResilience.classifyBreaker(CircuitBreakerConfig.custom()).build();
        return CircuitBreaker.of("test", config);
    }

    private static AuthorizationRequest authorizeRequest(PaymentId id) {
        return new AuthorizationRequest(id, Money.ofMinorUnits(15_000, Currency.INR), id + ":authorize");
    }

    private static CaptureRequest captureRequest(PaymentId id, String holdId, long amountMinor) {
        return new CaptureRequest(
                id,
                holdId,
                Money.ofMinorUnits(amountMinor, Currency.INR),
                id + ":capture");
    }

    private void stubAuthorize201(String authId, PaymentId paymentId) {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/v1/authorizations"))
                .withRequestBody(containing(paymentId.toString()))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":"%s","status":"approved"}
                                """.formatted(authId))));
    }
}

package com.payflow.payment.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresTestSupport;
import com.payflow.payment.application.port.out.ProcessorResult;
import com.payflow.payment.application.service.ScriptedPaymentProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentProcessorOutcomeIntegrationTest.ProcessorTestConfig.class)
class PaymentProcessorOutcomeIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedPaymentProcessor processor;

    private final String merchantId = UUID.randomUUID().toString();

    @BeforeEach
    void resetProcessor() {
        processor.reset();
    }

    @Test
    void authorizeReturnsProcessorReference() throws Exception {
        String id = paymentId(createPayment(5_000));
        processor.enqueueAuthorize(new ProcessorResult.Approved("psp_live_hold"));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command("auth-ref-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processorReference").value("psp_live_hold"));
    }

    @Test
    void declinedAuthorizeIsStoredAndReplayed() throws Exception {
        String id = paymentId(createPayment(5_000));
        processor.enqueueAuthorize(new ProcessorResult.Declined("card_declined", "Do not honor"));
        HttpHeaders headers = command("decline-key");

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(headers))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_DECLINED"));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(headers))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_DECLINED"));
    }

    @Test
    void unavailableAuthorizeReleasesTheKeyForRetry() throws Exception {
        String id = paymentId(createPayment(5_000));
        HttpHeaders headers = command("retry-key");
        processor.enqueueAuthorize(new ProcessorResult.Unavailable("down"));
        processor.enqueueAuthorize(new ProcessorResult.Approved("psp_after_retry"));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(headers))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(headers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processorReference").value("psp_after_retry"));
    }

    @Test
    void cancelDuringAuthorizeReturns409AndLeavesCancelled() throws Exception {
        String id = paymentId(createPayment(5_000));
        processor.blockAuthorizeUntilReleased();
        processor.enqueueAuthorize(new ProcessorResult.Approved("orphan_hold"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> authorizeFuture = executor.submit(() -> mockMvc.perform(
                            post("/v1/payments/{id}/authorize", id).headers(command("race-auth")))
                    .andReturn());

            Thread.sleep(200);
            mockMvc.perform(post("/v1/payments/{id}/cancel", id).headers(command("race-cancel")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            processor.releaseAuthorizeBlock();
            MvcResult authorizeResult = authorizeFuture.get();
            assertThat(authorizeResult.getResponse().getStatus()).isEqualTo(409);
            String errorCode = JsonPath.read(authorizeResult.getResponse().getContentAsString(), "$.errorCode");
            assertThat(errorCode).isEqualTo("CONCURRENT_MODIFICATION");

            mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command("after-race")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        } finally {
            processor.releaseAuthorizeBlock();
            executor.shutdownNow();
        }
    }

    private MvcResult createPayment(long amountMinor) throws Exception {
        return mockMvc.perform(post("/v1/payments").headers(command("create-" + amountMinor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": " + amountMinor + ", \"currency\": \"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String paymentId(MvcResult created) throws Exception {
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private HttpHeaders command(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, merchantId);
        headers.set(PayFlowHeaders.ENVIRONMENT, "TEST");
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    @TestConfiguration
    static class ProcessorTestConfig {

        @Bean
        @Primary
        ScriptedPaymentProcessor scriptedPaymentProcessor() {
            return new ScriptedPaymentProcessor();
        }
    }
}

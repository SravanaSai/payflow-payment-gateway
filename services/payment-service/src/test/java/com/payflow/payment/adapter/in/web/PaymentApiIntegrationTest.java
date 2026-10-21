package com.payflow.payment.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payment behaviour over HTTP. Idempotency itself is covered by the tests in the idempotency package. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIntegrationTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private final String merchantId = UUID.randomUUID().toString();

    @Test
    void lifecycleFromCreateThroughPartialCapture() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/payments").headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor": 150000, "currency": "INR",
                                 "description": "Order #1234", "reference": "order-1234"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountMinor").value(150000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(0))
                .andExpect(jsonPath("$.environment").value("TEST"))
                .andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/v1/payments/{id}", id).headers(identity(merchantId, "TEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("order-1234"));
        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command(merchantId, "TEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.processorReference").value("mem_" + id));
        mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmountMinor").value(100000));
    }

    @Test
    void createReturnsARelativeLocationForTheNewPayment() throws Exception {
        MvcResult created = createPayment(merchantId, "TEST", 5_000);
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(created.getResponse().getHeader(HttpHeaders.LOCATION))
                        .headers(identity(merchantId, "TEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void captureWithoutABodyTakesTheFullAmount() throws Exception {
        String id = authorizedPayment(5_000);

        mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command(merchantId, "TEST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAmountMinor").value(5_000));
    }

    @Test
    void cancellingAnAuthorizationRecordsTheReason() throws Exception {
        String id = authorizedPayment(5_000);

        mockMvc.perform(post("/v1/payments/{id}/cancel", id).headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"out of stock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("out of stock"));
    }

    @Test
    void capturingAPendingPaymentIsAnIllegalTransition() throws Exception {
        String id = paymentId(createPayment(merchantId, "TEST", 5_000));

        mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command(merchantId, "TEST")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void capturingMoreThanWasAuthorizedIsRejected() throws Exception {
        String id = authorizedPayment(5_000);

        mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 5001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherMerchantGets404NotForbidden() throws Exception {
        String id = paymentId(createPayment(merchantId, "TEST", 5_000));

        mockMvc.perform(get("/v1/payments/{id}", id).headers(identity(UUID.randomUUID().toString(), "TEST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void theSameMerchantsLiveKeyCannotSeeATestPayment() throws Exception {
        String id = paymentId(createPayment(merchantId, "TEST", 5_000));

        mockMvc.perform(get("/v1/payments/{id}", id).headers(identity(merchantId, "LIVE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void missingIdentityHeadersAre401() throws Exception {
        mockMvc.perform(get("/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void malformedIdentityHeadersAre401() throws Exception {
        mockMvc.perform(get("/v1/payments/{id}", UUID.randomUUID()).headers(identity("not-a-uuid", "TEST")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mockMvc.perform(get("/v1/payments/{id}", UUID.randomUUID()).headers(identity(merchantId, "PROD")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void unknownCurrencyIs400NotA500() throws Exception {
        mockMvc.perform(post("/v1/payments").headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100, \"currency\": \"XYZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void nonPositiveAmountIs400AndNamesTheField() throws Exception {
        mockMvc.perform(post("/v1/payments").headers(command(merchantId, "TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 0, \"currency\": \"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(containsString("amountMinor")));
    }

    @Test
    void badPaymentIdInThePathIs400NotA500() throws Exception {
        mockMvc.perform(get("/v1/payments/not-a-uuid").headers(identity(merchantId, "TEST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void healthCheckNeedsNoIdentity() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    private MvcResult createPayment(String merchant, String environment, long amountMinor) throws Exception {
        return mockMvc.perform(post("/v1/payments").headers(command(merchant, environment))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": " + amountMinor + ", \"currency\": \"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String authorizedPayment(long amountMinor) throws Exception {
        String id = paymentId(createPayment(merchantId, "TEST", amountMinor));
        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command(merchantId, "TEST")))
                .andExpect(status().isOk());
        return id;
    }

    private static String paymentId(MvcResult created) throws Exception {
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private static HttpHeaders identity(String merchant, String environment) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, merchant);
        headers.set(PayFlowHeaders.ENVIRONMENT, environment);
        return headers;
    }

    /** Identity plus a fresh Idempotency-Key, as every state-changing request must carry. */
    private static HttpHeaders command(String merchant, String environment) {
        HttpHeaders headers = identity(merchant, environment);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}

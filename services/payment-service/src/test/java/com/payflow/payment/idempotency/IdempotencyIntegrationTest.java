package com.payflow.payment.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.payflow.payment.idempotency.IdempotencyFilter.KEY_HEADER;
import static com.payflow.payment.idempotency.IdempotencyFilter.REPLAYED_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest extends PostgresTestSupport {

    private static final String CREATE_BODY = "{\"amountMinor\": 5000, \"currency\": \"INR\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String merchantId = UUID.randomUUID().toString();

    @Test
    void aPostWithoutAKeyIsRejectedAndChangesNothing() throws Exception {
        mockMvc.perform(post("/v1/payments").headers(identity("TEST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThat(paymentsOwnedByThisMerchant()).isZero();
    }

    @Test
    void readsNeedNoKey() throws Exception {
        String id = paymentId(create("TEST", newKey(), CREATE_BODY));

        mockMvc.perform(get("/v1/payments/{id}", id).headers(identity("TEST")))
                .andExpect(status().isOk());
    }

    @Test
    void aRetriedCreateReplaysTheOriginalResponseInsteadOfChargingTwice() throws Exception {
        String key = newKey();

        MvcResult first = create("TEST", key, CREATE_BODY);
        MvcResult retry = create("TEST", key, CREATE_BODY);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(retry.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(first.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(retry.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
        assertThat(paymentsOwnedByThisMerchant()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentBodyIsAConflict() throws Exception {
        String key = newKey();
        create("TEST", key, CREATE_BODY);

        mockMvc.perform(post("/v1/payments").headers(command("TEST", key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 9999, \"currency\": \"INR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
        assertThat(paymentsOwnedByThisMerchant()).isEqualTo(1);
    }

    @Test
    void reusingAKeyOnAnotherEndpointIsAConflict() throws Exception {
        String key = newKey();
        String id = paymentId(create("TEST", key, CREATE_BODY));

        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command("TEST", key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void testAndLiveKeysNeverCollide() throws Exception {
        String key = newKey();

        MvcResult test = create("TEST", key, CREATE_BODY);
        MvcResult live = create("LIVE", key, CREATE_BODY);

        assertThat(live.getResponse().getStatus()).isEqualTo(201);
        assertThat(live.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(paymentId(live)).isNotEqualTo(paymentId(test));
    }

    @Test
    void aStoredErrorReplaysEvenAfterThePaymentChanges() throws Exception {
        String id = paymentId(create("TEST", newKey(), CREATE_BODY));
        String captureKey = newKey();

        capture(id, captureKey).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/payments/{id}/authorize", id).headers(command("TEST", newKey())))
                .andExpect(status().isOk());

        capture(id, captureKey)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        mockMvc.perform(get("/v1/payments/{id}", id).headers(identity("TEST")))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void anExpiredKeyRunsAsANewRequest() throws Exception {
        String key = newKey();
        String firstId = paymentId(create("TEST", key, CREATE_BODY));
        jdbcTemplate.update("UPDATE idempotency_records SET expires_at = created_at"
                + " WHERE merchant_id = ? AND idempotency_key = ?", UUID.fromString(merchantId), key);

        MvcResult again = create("TEST", key, CREATE_BODY);

        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader(REPLAYED_HEADER)).isNull();
        assertThat(paymentId(again)).isNotEqualTo(firstId);
    }

    private MvcResult create(String environment, String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/payments").headers(command(environment, key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private ResultActions capture(String id, String key) throws Exception {
        return mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command("TEST", key)));
    }

    private int paymentsOwnedByThisMerchant() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payments WHERE merchant_id = ?",
                Integer.class, UUID.fromString(merchantId));
    }

    private HttpHeaders identity(String environment) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, merchantId);
        headers.set(PayFlowHeaders.ENVIRONMENT, environment);
        return headers;
    }

    private HttpHeaders command(String environment, String key) {
        HttpHeaders headers = identity(environment);
        headers.set(KEY_HEADER, key);
        return headers;
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static String paymentId(MvcResult created) throws Exception {
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }
}

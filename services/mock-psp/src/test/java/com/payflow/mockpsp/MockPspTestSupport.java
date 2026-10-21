package com.payflow.mockpsp;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * One shared Spring context for every mock-psp test. The stores live in memory and are shared too,
 * so each test uses fresh idempotency keys and its own holds instead of resetting anything.
 */
@SpringBootTest(properties = {
        "mockpsp.api-key=test_psp_key",
        "mockpsp.slow-delay=600ms",
        "mockpsp.flaky-failures=2"
})
@AutoConfigureMockMvc
abstract class MockPspTestSupport {

    static final String API_KEY = "test_psp_key";
    static final long SLOW_DELAY_MS = 600;
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    @Autowired
    protected MockMvc mockMvc;

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static String authorizeBody(long amountMinor) {
        return "{\"amount_minor\": " + amountMinor + ", \"currency\": \"INR\", \"merchant_reference\": \"ref-1\"}";
    }

    protected ResultActions authorize(long amountMinor, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/v1/authorizations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(amountMinor)));
    }

    protected ResultActions capture(String authorizationId, long amountMinor, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/v1/authorizations/{id}/captures", authorizationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount_minor\": " + amountMinor + "}"));
    }

    protected ResultActions getHold(String authorizationId) throws Exception {
        return mockMvc.perform(get("/v1/authorizations/{id}", authorizationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY));
    }

    /** Places an approved hold and returns its id. */
    protected String createHold(long amountMinor) throws Exception {
        return idOf(authorize(amountMinor, newKey()).andReturn());
    }

    protected static String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    protected static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}

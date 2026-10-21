package com.payflow.mockpsp;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorrelationIdTest extends MockPspTestSupport {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Test
    void echoesClientCorrelationIdOnSuccess() throws Exception {
        String correlationId = "test-corr-approve";

        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", newKey())
                        .header(CORRELATION_HEADER, correlationId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(authorizeBody(150_000)))
                .andExpect(status().isCreated())
                .andExpect(header().string(CORRELATION_HEADER, correlationId));
    }

    @Test
    void echoesClientCorrelationIdOnUnauthorized() throws Exception {
        String correlationId = "test-corr-401";

        mockMvc.perform(post("/v1/authorizations")
                        .header(CORRELATION_HEADER, correlationId)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .content(authorizeBody(150_000)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CORRELATION_HEADER, correlationId));
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CORRELATION_HEADER));
    }
}

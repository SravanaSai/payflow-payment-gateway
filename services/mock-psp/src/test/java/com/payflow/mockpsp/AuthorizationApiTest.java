package com.payflow.mockpsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthorizationApiTest extends MockPspTestSupport {

    @Test
    void approvalReturnsTheProcessorsOwnSnakeCaseShape() throws Exception {
        authorize(150_000, newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(startsWith("auth_")))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.amount_minor").value(150_000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.captured_amount_minor").value(0))
                .andExpect(jsonPath("$.merchant_reference").value("ref-1"))
                .andExpect(jsonPath("$.created_at").exists())
                // PayFlow's camelCase names must not leak into the processor's vocabulary.
                .andExpect(jsonPath("$.amountMinor").doesNotExist())
                .andExpect(jsonPath("$.capturedAmountMinor").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"150051, insufficient_funds", "150052, do_not_honor"})
    void declinesAre402WithACardErrorBody(long amountMinor, String declineCode) throws Exception {
        authorize(amountMinor, newKey())
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.type").value("card_error"))
                .andExpect(jsonPath("$.error.code").value("card_declined"))
                .andExpect(jsonPath("$.error.decline_code").value(declineCode))
                .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void aMissingApiKeyIs401() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody(150_000)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.type").value("authentication_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void aWrongApiKeyIs401() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not_the_key")
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody(150_000)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
    }

    @Test
    void aKeyThatIsNotABearerTokenIs401() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody(150_000)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readingAHoldAlsoNeedsTheApiKey() throws Exception {
        String id = createHold(150_000);

        mockMvc.perform(get("/v1/authorizations/{id}", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthNeedsNoApiKey() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void aMissingIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authorizeBody(150_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.message").value(containsString("Idempotency-Key")));
    }

    @Test
    void aBlankIdempotencyKeyIs400() throws Exception {
        authorize(150_000, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount_minor\": 0, \"currency\": \"INR\"}",
            "{\"amount_minor\": -5, \"currency\": \"INR\"}",
            "{\"currency\": \"INR\"}",
            "{\"amount_minor\": 100, \"currency\": \"inr\"}",
            "{\"amount_minor\": 100}"
    })
    void invalidBodiesAre400NeverA500(String json) throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void malformedJsonIs400NotA500() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void anEmptyBodyIs400NotA500() throws Exception {
        mockMvc.perform(post("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void anUnknownPathIs404InTheProcessorsFormat() throws Exception {
        mockMvc.perform(get("/v1/nothing-here")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }

    @Test
    void aWrongMethodIs405InTheProcessorsFormat() throws Exception {
        mockMvc.perform(get("/v1/authorizations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"));
    }

    @Test
    void anApprovedHoldCanBeReadBack() throws Exception {
        String id = createHold(150_000);

        getHold(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.amount_minor").value(150_000))
                .andExpect(jsonPath("$.captured_amount_minor").value(0));
    }

    @Test
    void anUnknownHoldIs404() throws Exception {
        getHold("auth_does-not-exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }
}

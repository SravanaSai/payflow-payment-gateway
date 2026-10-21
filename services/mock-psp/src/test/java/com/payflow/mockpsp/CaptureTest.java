package com.payflow.mockpsp;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CaptureTest extends MockPspTestSupport {

    @Test
    void aFullCaptureSucceedsAndIsVisibleOnTheHold() throws Exception {
        String holdId = createHold(150_000);

        capture(holdId, 150_000, newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(startsWith("cap_")))
                .andExpect(jsonPath("$.authorization_id").value(holdId))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.amount_minor").value(150_000));

        getHold(holdId)
                .andExpect(jsonPath("$.status").value("captured"))
                .andExpect(jsonPath("$.captured_amount_minor").value(150_000));
    }

    @Test
    void aPartialCaptureRecordsOnlyWhatWasTaken() throws Exception {
        String holdId = createHold(150_000);

        capture(holdId, 100_000, newKey()).andExpect(status().isCreated());

        getHold(holdId)
                .andExpect(jsonPath("$.status").value("captured"))
                .andExpect(jsonPath("$.amount_minor").value(150_000))
                .andExpect(jsonPath("$.captured_amount_minor").value(100_000));
    }

    @Test
    void aHoldCanBeCapturedOnlyOnce() throws Exception {
        String holdId = createHold(150_000);
        capture(holdId, 100_000, newKey()).andExpect(status().isCreated());

        capture(holdId, 50_000, newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("authorization_already_captured"));

        getHold(holdId).andExpect(jsonPath("$.captured_amount_minor").value(100_000));
    }

    @Test
    void capturingMoreThanTheHoldIsRejectedAndLeavesTheHoldCapturable() throws Exception {
        String holdId = createHold(150_000);

        capture(holdId, 150_001, newKey())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("amount_too_large"));

        getHold(holdId).andExpect(jsonPath("$.status").value("approved"));
        capture(holdId, 150_000, newKey()).andExpect(status().isCreated());
    }

    @Test
    void anUnknownHoldIs404() throws Exception {
        capture("auth_does-not-exist", 1_000, newKey())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("resource_missing"));
    }

    @Test
    void aDeclinedCaptureLeavesTheHoldCapturable() throws Exception {
        String holdId = createHold(150_000);

        capture(holdId, 100_051, newKey())
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error.decline_code").value("insufficient_funds"));

        getHold(holdId)
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.captured_amount_minor").value(0));
        capture(holdId, 100_000, newKey()).andExpect(status().isCreated());
    }

    @Test
    void anUnavailableCaptureChangesNothing() throws Exception {
        String holdId = createHold(150_000);

        capture(holdId, 100_092, newKey()).andExpect(status().isServiceUnavailable());

        getHold(holdId)
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.captured_amount_minor").value(0));
    }

    @Test
    void retryingACaptureWithTheSameKeyReplaysItInsteadOfFailingAsAlreadyCaptured() throws Exception {
        String holdId = createHold(150_000);
        String key = newKey();

        MvcResult first = capture(holdId, 100_000, key)
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = capture(holdId, 100_000, key)
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andReturn();

        assertThat(bodyOf(second)).isEqualTo(bodyOf(first));
    }

    @Test
    void reusingACaptureKeyForADifferentAmountIs400() throws Exception {
        String holdId = createHold(150_000);
        String key = newKey();
        capture(holdId, 100_000, key).andExpect(status().isCreated());

        capture(holdId, 90_000, key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    }

    @Test
    void aFailedValidationDoesNotUseUpTheKey() throws Exception {
        String holdId = createHold(150_000);
        String key = newKey();

        capture(holdId, 150_001, key).andExpect(status().isBadRequest());

        // The rejected request was never recorded, so the same key can carry a valid request.
        capture(holdId, 150_000, key).andExpect(status().isCreated());
    }
}

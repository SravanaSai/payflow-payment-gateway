package com.payflow.mockpsp;

import com.payflow.mockpsp.idempotency.IdempotencyStore;
import com.payflow.mockpsp.idempotency.IdempotencyStore.Fingerprint;
import com.payflow.mockpsp.web.dto.AuthorizeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyTest extends MockPspTestSupport {

    @Autowired
    private IdempotencyStore store;

    @Test
    void theSameKeyAndRequestReplaysTheSameHoldInsteadOfCreatingAnother() throws Exception {
        String key = newKey();

        MvcResult first = authorize(150_000, key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED_HEADER))
                .andReturn();
        MvcResult second = authorize(150_000, key)
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andReturn();

        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(bodyOf(second)).isEqualTo(bodyOf(first));
    }

    @Test
    void aDifferentKeyCreatesADifferentHold() throws Exception {
        MvcResult first = authorize(150_000, newKey()).andReturn();
        MvcResult second = authorize(150_000, newKey()).andReturn();

        assertThat(idOf(second)).isNotEqualTo(idOf(first));
    }

    @Test
    void reusingAKeyForADifferentRequestIs400() throws Exception {
        String key = newKey();
        authorize(150_000, key).andExpect(status().isCreated());

        authorize(150_001, key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("idempotency_error"))
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    }

    @Test
    void aDeclineIsReplayedAsTheSameDecline() throws Exception {
        String key = newKey();

        MvcResult first = authorize(150_051, key)
                .andExpect(status().isPaymentRequired())
                .andExpect(header().doesNotExist(REPLAYED_HEADER))
                .andReturn();
        MvcResult second = authorize(150_051, key)
                .andExpect(status().isPaymentRequired())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.error.decline_code").value("insufficient_funds"))
                .andReturn();

        assertThat(bodyOf(second)).isEqualTo(bodyOf(first));
    }

    @Test
    void whenDownEveryAttemptFailsAndTheKeyStaysFree() throws Exception {
        String key = newKey();

        for (int attempt = 1; attempt <= 3; attempt++) {
            authorize(150_092, key)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.type").value("api_error"))
                    .andExpect(jsonPath("$.error.code").value("service_unavailable"));
        }

        // Nothing was recorded, so the same key is still free for a different request.
        authorize(150_000, key).andExpect(status().isCreated());
    }

    @Test
    void aFlakyAmountFailsTwiceThenApprovesAndThenReplays() throws Exception {
        String key = newKey();

        authorize(150_093, key).andExpect(status().isServiceUnavailable());
        authorize(150_093, key).andExpect(status().isServiceUnavailable());
        MvcResult approved = authorize(150_093, key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED_HEADER))
                .andReturn();

        authorize(150_093, key)
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(idOf(approved)));
    }

    @Test
    void eachFlakyKeyCountsItsOwnAttempts() throws Exception {
        authorize(150_093, newKey()).andExpect(status().isServiceUnavailable());

        // A different key starts from zero, so it also fails first.
        authorize(150_093, newKey()).andExpect(status().isServiceUnavailable());
    }

    /**
     * The hardest real-world case: the processor placed the hold but its reply is slow. The caller
     * gives up and retries with the same key. The hold must already be on record, so the retry gets
     * the same one while the first reply is still on its way.
     */
    @Test
    void aSlowReplyIsRecordedSoARetryDuringTheWaitGetsTheSameHold() throws Exception {
        String key = newKey();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            long start = System.nanoTime();
            Future<MvcResult> original = executor.submit(() -> authorize(150_091, key).andReturn());
            Thread.sleep(50); // let the original call claim the key first

            MvcResult retry;
            do {
                retry = authorize(150_091, key).andReturn();
                // 409 only if the original has not stored its answer yet; try again shortly.
            } while (retry.getResponse().getStatus() == 409 && sleepQuietly(20));
            long retryAnsweredAtMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(retry.getResponse().getStatus()).isEqualTo(201);
            assertThat(retry.getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
            assertThat(retryAnsweredAtMs)
                    .as("the retry must be answered while the original is still waiting")
                    .isLessThan(SLOW_DELAY_MS * 3 / 4);

            MvcResult finished = original.get(5, TimeUnit.SECONDS);
            long originalAnsweredAtMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(finished.getResponse().getStatus()).isEqualTo(201);
            assertThat(idOf(finished)).isEqualTo(idOf(retry));
            assertThat(originalAnsweredAtMs)
                    .as("the original reply really was slow")
                    .isGreaterThanOrEqualTo(SLOW_DELAY_MS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aKeyStillInProgressIs409() throws Exception {
        String key = newKey();
        // Claim the key the way an in-flight identical request would have.
        store.claim(key, new Fingerprint("/v1/authorizations", new AuthorizeRequest(150_000, "INR", "ref-1")));

        authorize(150_000, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("idempotency_error"))
                .andExpect(jsonPath("$.error.code").value("idempotency_key_in_use"));
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

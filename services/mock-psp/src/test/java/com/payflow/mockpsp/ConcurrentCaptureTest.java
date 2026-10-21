package com.payflow.mockpsp;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ConcurrentCaptureTest extends MockPspTestSupport {

    private static final int CALLERS = 10;

    @Test
    void ofTenSimultaneousCapturesOfOneHoldExactlyOneSucceeds() throws Exception {
        String holdId = createHold(150_000);
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CALLERS);
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < CALLERS; i++) {
                // A distinct key per caller: these are ten different requests, not ten retries.
                String key = newKey();
                calls.add(executor.submit(() -> {
                    startTogether.await();
                    return capture(holdId, 100_000, key).andReturn();
                }));
            }
            startTogether.countDown();

            int succeeded = 0;
            for (Future<MvcResult> call : calls) {
                MvcResult result = call.get(10, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                if (status == 201) {
                    succeeded++;
                } else {
                    assertThat(status).isEqualTo(400);
                    String code = JsonPath.read(result.getResponse().getContentAsString(), "$.error.code");
                    assertThat(code).isEqualTo("authorization_already_captured");
                }
            }

            assertThat(succeeded).as("captures that succeeded").isEqualTo(1);
            getHold(holdId)
                    .andExpect(jsonPath("$.status").value("captured"))
                    .andExpect(jsonPath("$.captured_amount_minor").value(100_000));
        } finally {
            executor.shutdownNow();
        }
    }
}

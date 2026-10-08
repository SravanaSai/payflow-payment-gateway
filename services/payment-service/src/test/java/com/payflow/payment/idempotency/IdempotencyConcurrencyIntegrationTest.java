package com.payflow.payment.idempotency;

import com.jayway.jsonpath.JsonPath;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.PostgresTestSupport;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import com.payflow.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.payflow.payment.domain.model.PaymentFixtures.inStatus;
import static com.payflow.payment.idempotency.IdempotencyFilter.KEY_HEADER;
import static com.payflow.payment.idempotency.IdempotencyFilter.REPLAYED_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Races made deterministic by pausing or interfering with the repository at a chosen moment. */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyConcurrencyIntegrationTest extends PostgresTestSupport {

    @MockitoSpyBean
    private PaymentRepositoryPort repository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final PaymentOwner owner = new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST);

    @Test
    void aDuplicateArrivingWhileTheFirstIsStillRunningIsToldToRetryLater() throws Exception {
        String key = UUID.randomUUID().toString();
        String reference = "race-" + key;
        String body = "{\"amountMinor\": 5000, \"currency\": \"INR\", \"reference\": \"" + reference + "\"}";
        CountDownLatch firstIsSaving = new CountDownLatch(1);
        CountDownLatch letFirstFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstIsSaving.countDown();
            letFirstFinish.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(repository).save(argThat((Payment payment) -> reference.equals(payment.getReference())));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> create(key, body));
            assertThat(firstIsSaving.await(10, TimeUnit.SECONDS)).isTrue();

            MvcResult duplicate = create(key, body);
            letFirstFinish.countDown();

            assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(duplicate.getResponse().getContentAsString(), "$.errorCode"))
                    .isEqualTo("IDEMPOTENCY_IN_PROGRESS");
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            letFirstFinish.countDown();
            executor.shutdownNow();
        }

        assertThat(create(key, body).getResponse().getHeader(REPLAYED_HEADER)).isEqualTo("true");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payments WHERE reference = ?",
                Integer.class, reference)).isEqualTo(1);
    }

    @Test
    void aLostRaceFreesTheKeySoTheRetryRunsAgain() throws Exception {
        UUID id = repository.save(inStatus(PaymentStatus.AUTHORIZED, owner)).getId().value();
        TransactionTemplate competingRequest = new TransactionTemplate(transactionManager);
        competingRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicBoolean raceAlreadyLost = new AtomicBoolean();
        doAnswer(invocation -> {
            if (raceAlreadyLost.compareAndSet(false, true)) {
                competingRequest.executeWithoutResult(status -> jdbcTemplate.update(
                        "UPDATE payments SET version = version + 1 WHERE id = ?", id));
            }
            return invocation.callRealMethod();
        }).when(repository).save(argThat((Payment payment) -> payment.getStatus() == PaymentStatus.CAPTURED));
        String key = UUID.randomUUID().toString();

        capture(id, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
        capture(id, key)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(REPLAYED_HEADER))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    private MvcResult create(String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/payments").headers(command(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private ResultActions capture(UUID id, String key) throws Exception {
        return mockMvc.perform(post("/v1/payments/{id}/capture", id).headers(command(key)));
    }

    private HttpHeaders command(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PayFlowHeaders.MERCHANT_ID, owner.merchantId().toString());
        headers.set(PayFlowHeaders.ENVIRONMENT, owner.environment().name());
        headers.set(KEY_HEADER, key);
        return headers;
    }
}

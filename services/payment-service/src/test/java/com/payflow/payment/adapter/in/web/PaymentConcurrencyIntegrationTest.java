package com.payflow.payment.adapter.in.web;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.payflow.payment.domain.model.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reproduces a lost race deterministically: the moment the capture is about to save, a
 * competing transaction commits first. Kept in its own class because the spy gives this
 * test a different Spring context from the rest of the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentConcurrencyIntegrationTest extends PostgresTestSupport {

    @MockitoSpyBean
    private PaymentRepositoryPort repository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aWriteThatLosesTheRaceIsA409NotA500() throws Exception {
        PaymentOwner owner = new PaymentOwner(MerchantId.generate(), PaymentEnvironment.TEST);
        UUID id = repository.save(inStatus(PaymentStatus.AUTHORIZED, owner)).getId().value();

        TransactionTemplate competingRequest = new TransactionTemplate(transactionManager);
        competingRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            competingRequest.executeWithoutResult(status -> jdbcTemplate.update(
                    "UPDATE payments SET status = 'CANCELLED', version = version + 1 WHERE id = ?", id));
            return invocation.callRealMethod();
        }).when(repository).save(argThat((Payment payment) -> payment.getStatus() == PaymentStatus.CAPTURED));

        mockMvc.perform(post("/v1/payments/{id}/capture", id)
                        .header(PayFlowHeaders.MERCHANT_ID, owner.merchantId().toString())
                        .header(PayFlowHeaders.ENVIRONMENT, owner.environment().name())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
    }
}

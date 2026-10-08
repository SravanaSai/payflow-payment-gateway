package com.payflow.payment.adapter.out.persistence;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.PostgresTestSupport;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import com.payflow.payment.domain.model.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.payflow.payment.domain.model.PaymentFixtures.LATER;
import static com.payflow.payment.domain.model.PaymentFixtures.inStatus;
import static com.payflow.payment.domain.model.PaymentFixtures.newOwner;
import static com.payflow.payment.domain.model.PaymentStatus.AUTHORIZED;
import static com.payflow.payment.domain.model.PaymentStatus.CAPTURED;
import static com.payflow.payment.domain.model.PaymentStatus.FAILED;
import static com.payflow.payment.domain.model.PaymentStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the port against real Postgres with {@code ddl-auto: validate}, so the
 * context failing to start is itself a signal that the entity and migration disagree.
 */
@SpringBootTest
class PaymentRepositoryAdapterIntegrationTest extends PostgresTestSupport {

    @Autowired
    private PaymentRepositoryPort repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void everyStatusSurvivesARoundTrip(PaymentStatus status) {
        PaymentOwner owner = newOwner();
        Payment payment = inStatus(status, owner);

        Payment saved = repository.save(payment);
        Payment reloaded = repository.findById(saved.getId(), owner).orElseThrow();

        assertThat(saved.getVersion()).isZero();
        assertThat(saved).usingRecursiveComparison().ignoringFields("version").isEqualTo(payment);
        assertThat(reloaded).usingRecursiveComparison().isEqualTo(saved);
    }

    @Test
    void eachUpdateIncrementsTheVersion() {
        PaymentOwner owner = newOwner();
        Payment pending = repository.save(inStatus(PENDING, owner));

        pending.authorize(LATER);
        Payment authorized = repository.save(pending);
        authorized.capture(LATER);
        Payment captured = repository.save(authorized);

        assertThat(authorized.getVersion()).isEqualTo(1L);
        assertThat(captured.getVersion()).isEqualTo(2L);
        assertThat(repository.findById(captured.getId(), owner).orElseThrow().getStatus()).isEqualTo(CAPTURED);
    }

    @Test
    void staleWriteIsRejectedInsteadOfSilentlyOverwriting() {
        PaymentOwner owner = newOwner();
        PaymentId id = repository.save(inStatus(AUTHORIZED, owner)).getId();
        Payment firstRequest = repository.findById(id, owner).orElseThrow();
        Payment secondRequest = repository.findById(id, owner).orElseThrow();

        firstRequest.capture(LATER);
        repository.save(firstRequest);
        secondRequest.cancel("customer changed their mind", LATER);

        assertThatThrownBy(() -> repository.save(secondRequest)).isInstanceOfSatisfying(PayFlowException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        assertThat(repository.findById(id, owner).orElseThrow().getStatus()).isEqualTo(CAPTURED);
    }

    @Test
    void lookupIsScopedToMerchantAndEnvironment() {
        PaymentOwner owner = new PaymentOwner(MerchantId.generate(), PaymentEnvironment.LIVE);
        PaymentId id = repository.save(inStatus(PENDING, owner)).getId();

        assertThat(repository.findById(id, owner)).isPresent();
        assertThat(repository.findById(id, new PaymentOwner(MerchantId.generate(), PaymentEnvironment.LIVE)))
                .isEmpty();
        assertThat(repository.findById(id, new PaymentOwner(owner.merchantId(), PaymentEnvironment.TEST)))
                .isEmpty();
    }

    @Test
    void referenceCanBeReusedToRetryADeclinedOrder() {
        PaymentOwner owner = newOwner();
        Payment declined = repository.save(inStatus(FAILED, owner));
        Payment retry = repository.save(inStatus(PENDING, owner));

        assertThat(retry.getReference()).isEqualTo(declined.getReference());
        assertThat(repository.findById(retry.getId(), owner)).isPresent();
    }

    @Test
    void databaseRejectsCapturedMoneyOnAnUncapturedPayment() {
        PaymentId id = repository.save(inStatus(AUTHORIZED)).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET captured_amount_minor = 500 WHERE id = ?", id.value()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

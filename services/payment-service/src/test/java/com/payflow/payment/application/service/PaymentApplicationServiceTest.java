package com.payflow.payment.application.service;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;
import com.payflow.payment.application.port.in.CapturePaymentUseCase.CapturePaymentCommand;
import com.payflow.payment.application.port.in.CreatePaymentUseCase.CreatePaymentCommand;
import com.payflow.payment.application.port.out.ProcessorResult;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.payflow.common.core.error.ErrorCode.PAYMENT_DECLINED;
import static com.payflow.common.core.error.ErrorCode.PAYMENT_NOT_FOUND;
import static com.payflow.common.core.error.ErrorCode.SERVICE_UNAVAILABLE;
import static com.payflow.common.core.error.ErrorCode.VALIDATION_FAILED;
import static com.payflow.payment.domain.model.PaymentFixtures.inr;
import static com.payflow.payment.domain.model.PaymentFixtures.newOwner;
import static com.payflow.payment.domain.model.PaymentStatus.AUTHORIZED;
import static com.payflow.payment.domain.model.PaymentStatus.CAPTURED;
import static com.payflow.payment.domain.model.PaymentStatus.FAILED;
import static com.payflow.payment.domain.model.PaymentStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:30:00Z");

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    private final ScriptedPaymentProcessor processor = new ScriptedPaymentProcessor();
    private final PaymentApplicationService service = new PaymentApplicationService(
            repository,
            processor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            readOnlyTemplate(),
            writeTemplate());

    private final PaymentOwner owner = newOwner();

    @BeforeEach
    void resetProcessor() {
        processor.reset();
    }

    @Test
    void createStampsTheClockTimeAndStoresThePayment() {
        Payment created = createPayment(5_000);

        assertThat(created.getStatus()).isEqualTo(PENDING);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(repository.findById(created.getId(), owner)).contains(created);
    }

    @Test
    void anotherMerchantCannotSeeThePayment() {
        Payment created = createPayment(5_000);

        assertRejected(() -> service.get(created.getId(), newOwner()), PAYMENT_NOT_FOUND);
    }

    @Test
    void theSameMerchantInTheOtherEnvironmentCannotSeeThePayment() {
        Payment created = createPayment(5_000);
        PaymentOwner live = new PaymentOwner(owner.merchantId(), PaymentEnvironment.LIVE);

        assertRejected(() -> service.get(created.getId(), live), PAYMENT_NOT_FOUND);
    }

    @Test
    void authorizeStoresTheProcessorReference() {
        Payment created = createPayment(5_000);
        processor.enqueueAuthorize(new ProcessorResult.Approved("psp_hold_1"));

        Payment authorized = service.authorize(new AuthorizePaymentCommand(created.getId(), owner));

        assertThat(authorized.getStatus()).isEqualTo(AUTHORIZED);
        assertThat(authorized.getProcessorReference()).isEqualTo("psp_hold_1");
        assertThat(processor.authorizeCalls()).hasSize(1);
        assertThat(processor.authorizeCalls().getFirst().outboundIdempotencyKey())
                .isEqualTo(created.getId().value() + ":authorize");
    }

    @Test
    void declinedAuthorizeMarksFailedThenSurfaces422() {
        Payment created = createPayment(5_000);
        processor.enqueueAuthorize(new ProcessorResult.Declined("insufficient_funds", "Not enough balance"));

        assertRejected(
                () -> service.authorize(new AuthorizePaymentCommand(created.getId(), owner)),
                PAYMENT_DECLINED);

        Payment reloaded = repository.findById(created.getId(), owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("Not enough balance");
    }

    @Test
    void unavailableAuthorizeLeavesPendingAndReturns503() {
        Payment created = createPayment(5_000);
        processor.enqueueAuthorize(new ProcessorResult.Unavailable("processor timeout"));

        assertRejected(
                () -> service.authorize(new AuthorizePaymentCommand(created.getId(), owner)),
                SERVICE_UNAVAILABLE);

        assertThat(repository.findById(created.getId(), owner).orElseThrow().getStatus()).isEqualTo(PENDING);
    }

    @Test
    void captureWithoutAnAmountTakesEverythingAuthorized() {
        Payment created = authorizedPayment(5_000);

        Payment captured = service.capture(new CapturePaymentCommand(created.getId(), owner, null));

        assertThat(captured.getStatus()).isEqualTo(CAPTURED);
        assertThat(captured.getCapturedAmount()).isEqualTo(inr(5_000));
        assertThat(processor.captureCalls().getFirst().processorReference()).isEqualTo("script_" + created.getId());
    }

    @Test
    void declinedCaptureDoesNotChangeAuthorizedPayment() {
        Payment created = authorizedPayment(5_000);
        processor.enqueueCapture(new ProcessorResult.Declined("capture_failed", "Capture rejected"));

        assertRejected(
                () -> service.capture(new CapturePaymentCommand(created.getId(), owner, null)),
                PAYMENT_DECLINED);

        Payment reloaded = repository.findById(created.getId(), owner).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AUTHORIZED);
        assertThat(reloaded.getCaptureAttempts()).isEqualTo(1);
    }

    @Test
    void captureAfterDeclineUsesNextProcessorKey() {
        Payment created = authorizedPayment(5_000);
        processor.enqueueCapture(
                new ProcessorResult.Declined("capture_failed", "Capture rejected"),
                new ProcessorResult.Approved("script_" + created.getId()));

        assertRejected(
                () -> service.capture(new CapturePaymentCommand(created.getId(), owner, null)),
                PAYMENT_DECLINED);
        Payment captured = service.capture(new CapturePaymentCommand(created.getId(), owner, null));

        assertThat(captured.getStatus()).isEqualTo(CAPTURED);
        assertThat(processor.captureCalls()).hasSize(2);
        assertThat(processor.captureCalls().get(0).outboundIdempotencyKey())
                .isEqualTo(created.getId().value() + ":capture");
        assertThat(processor.captureCalls().get(1).outboundIdempotencyKey())
                .isEqualTo(created.getId().value() + ":capture:1");
    }

    @Test
    void unavailableCaptureKeepsTheSameProcessorKey() {
        Payment created = authorizedPayment(5_000);
        processor.enqueueCapture(
                new ProcessorResult.Unavailable("timeout"),
                new ProcessorResult.Approved("script_" + created.getId()));

        assertRejected(
                () -> service.capture(new CapturePaymentCommand(created.getId(), owner, null)),
                SERVICE_UNAVAILABLE);
        Payment captured = service.capture(new CapturePaymentCommand(created.getId(), owner, null));

        assertThat(captured.getStatus()).isEqualTo(CAPTURED);
        assertThat(repository.findById(created.getId(), owner).orElseThrow().getCaptureAttempts()).isZero();
        assertThat(processor.captureCalls().get(0).outboundIdempotencyKey())
                .isEqualTo(processor.captureCalls().get(1).outboundIdempotencyKey());
    }

    @Test
    void captureWithAnAmountUsesThePaymentsCurrency() {
        Payment created = authorizedPayment(5_000);

        Payment captured = service.capture(new CapturePaymentCommand(created.getId(), owner, 2_000L));

        assertThat(captured.getCapturedAmount()).isEqualTo(inr(2_000));
    }

    @Test
    void commandingAnUnknownPaymentIsNotFound() {
        assertRejected(() -> service.authorize(new AuthorizePaymentCommand(PaymentId.generate(), owner)),
                PAYMENT_NOT_FOUND);
    }

    @Test
    void captureCommandRejectsANonPositiveAmount() {
        assertRejected(() -> new CapturePaymentCommand(PaymentId.generate(), owner, 0L), VALIDATION_FAILED);
    }

    @Test
    void authorizeDoesNotCallProcessorWhenStateIsIllegal() {
        Payment created = authorizedPayment(5_000);

        assertRejected(() -> service.authorize(new AuthorizePaymentCommand(created.getId(), owner)),
                ErrorCode.INVALID_STATE_TRANSITION);

        assertThat(processor.authorizeCalls()).hasSize(1);
    }

    private Payment createPayment(long amountMinor) {
        return service.create(new CreatePaymentCommand(owner, inr(amountMinor), "Mug", "order-9"));
    }

    private Payment authorizedPayment(long amountMinor) {
        Payment created = createPayment(amountMinor);
        return service.authorize(new AuthorizePaymentCommand(created.getId(), owner));
    }

    private static void assertRejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PayFlowException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private static TransactionTemplate readOnlyTemplate() {
        TransactionTemplate template = new TransactionTemplate(noOpTransactionManager());
        template.setReadOnly(true);
        return template;
    }

    private static TransactionTemplate writeTemplate() {
        return new TransactionTemplate(noOpTransactionManager());
    }

    private static PlatformTransactionManager noOpTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}

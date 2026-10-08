package com.payflow.payment.application.service;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.in.AuthorizePaymentUseCase.AuthorizePaymentCommand;
import com.payflow.payment.application.port.in.CapturePaymentUseCase.CapturePaymentCommand;
import com.payflow.payment.application.port.in.CreatePaymentUseCase.CreatePaymentCommand;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.payflow.common.core.error.ErrorCode.PAYMENT_NOT_FOUND;
import static com.payflow.common.core.error.ErrorCode.VALIDATION_FAILED;
import static com.payflow.payment.domain.model.PaymentFixtures.inr;
import static com.payflow.payment.domain.model.PaymentFixtures.newOwner;
import static com.payflow.payment.domain.model.PaymentStatus.CAPTURED;
import static com.payflow.payment.domain.model.PaymentStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:30:00Z");

    private final InMemoryPaymentRepository repository = new InMemoryPaymentRepository();
    private final PaymentApplicationService service =
            new PaymentApplicationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final PaymentOwner owner = newOwner();

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
    void captureWithoutAnAmountTakesEverythingAuthorized() {
        Payment created = authorizedPayment(5_000);

        Payment captured = service.capture(new CapturePaymentCommand(created.getId(), owner, null));

        assertThat(captured.getStatus()).isEqualTo(CAPTURED);
        assertThat(captured.getCapturedAmount()).isEqualTo(inr(5_000));
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
}

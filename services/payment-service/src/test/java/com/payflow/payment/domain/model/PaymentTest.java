package com.payflow.payment.domain.model;

import com.payflow.common.core.domain.Currency;
import com.payflow.common.core.domain.Money;
import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static com.payflow.common.core.error.ErrorCode.CURRENCY_MISMATCH;
import static com.payflow.common.core.error.ErrorCode.INVALID_STATE_TRANSITION;
import static com.payflow.common.core.error.ErrorCode.VALIDATION_FAILED;
import static com.payflow.payment.domain.model.PaymentFixtures.AMOUNT;
import static com.payflow.payment.domain.model.PaymentFixtures.CREATED_AT;
import static com.payflow.payment.domain.model.PaymentFixtures.LATER;
import static com.payflow.payment.domain.model.PaymentFixtures.inStatus;
import static com.payflow.payment.domain.model.PaymentFixtures.inr;
import static com.payflow.payment.domain.model.PaymentFixtures.newOwner;
import static com.payflow.payment.domain.model.PaymentStatus.AUTHORIZED;
import static com.payflow.payment.domain.model.PaymentStatus.CANCELLED;
import static com.payflow.payment.domain.model.PaymentStatus.CAPTURED;
import static com.payflow.payment.domain.model.PaymentStatus.FAILED;
import static com.payflow.payment.domain.model.PaymentStatus.PENDING;
import static com.payflow.payment.domain.model.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Nested
    class Create {

        @Test
        void startsPendingWithNothingCaptured() {
            Payment payment = inStatus(PENDING);

            assertThat(payment.getStatus()).isEqualTo(PENDING);
            assertThat(payment.getCapturedAmount()).isEqualTo(inr(0));
            assertThat(payment.getVersion()).isNull();
            assertThat(payment.getCreatedAt()).isEqualTo(CREATED_AT);
            assertThat(payment.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void rejectsZeroAmount() {
            assertRejected(() -> Payment.create(newOwner(), inr(0), null, null, CREATED_AT), VALIDATION_FAILED);
        }

        @Test
        void trimsTextAndTreatsBlankAsAbsent() {
            Payment payment = Payment.create(newOwner(), inr(AMOUNT), "   ", "  order-7  ", CREATED_AT);

            assertThat(payment.getDescription()).isNull();
            assertThat(payment.getReference()).isEqualTo("order-7");
        }

        @Test
        void acceptsReferenceAtTheLengthLimit() {
            String atLimit = "r".repeat(Payment.MAX_REFERENCE_LENGTH);

            assertThat(Payment.create(newOwner(), inr(AMOUNT), null, atLimit, CREATED_AT).getReference())
                    .isEqualTo(atLimit);
        }

        @Test
        void rejectsReferenceOneOverTheLimit() {
            String overLimit = "r".repeat(Payment.MAX_REFERENCE_LENGTH + 1);

            assertRejected(() -> Payment.create(newOwner(), inr(AMOUNT), null, overLimit, CREATED_AT),
                    VALIDATION_FAILED);
        }
    }

    @Nested
    class Authorize {

        @Test
        void movesPendingToAuthorized() {
            Payment payment = inStatus(PENDING);

            payment.authorize("psp-ref-1", LATER);

            assertThat(payment.getStatus()).isEqualTo(AUTHORIZED);
            assertThat(payment.getProcessorReference()).isEqualTo("psp-ref-1");
            assertThat(payment.getUpdatedAt()).isEqualTo(LATER);
            assertThat(payment.getCreatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        void cannotAuthorizeTwice() {
            Payment payment = inStatus(AUTHORIZED);

            assertRejectedUnchanged(payment, () -> payment.authorize("psp-ref-1", LATER), INVALID_STATE_TRANSITION);
        }

        @Test
        void rejectsBlankProcessorReference() {
            Payment payment = inStatus(PENDING);

            assertRejected(() -> payment.authorize("  ", LATER), VALIDATION_FAILED);
        }

        @Test
        void rejectsProcessorReferenceOverTheLimit() {
            Payment payment = inStatus(PENDING);
            String over = "r".repeat(Payment.MAX_PROCESSOR_REFERENCE_LENGTH + 1);

            assertRejected(() -> payment.authorize(over, LATER), VALIDATION_FAILED);
        }
    }

    @Nested
    class Capture {

        @Test
        void capturesTheFullAmountByDefault() {
            Payment payment = inStatus(AUTHORIZED);

            payment.capture(LATER);

            assertThat(payment.getStatus()).isEqualTo(CAPTURED);
            assertThat(payment.getCapturedAmount()).isEqualTo(payment.getAmount());
            assertThat(payment.getUpdatedAt()).isEqualTo(LATER);
        }

        @Test
        void capturesPartOfTheAuthorizedAmount() {
            Payment payment = inStatus(AUTHORIZED);

            payment.capture(inr(4_000), LATER);

            assertThat(payment.getCapturedAmount()).isEqualTo(inr(4_000));
        }

        @Test
        void rejectsMoreThanWasAuthorized() {
            Payment payment = inStatus(AUTHORIZED);

            assertRejectedUnchanged(payment, () -> payment.capture(inr(AMOUNT + 1), LATER), VALIDATION_FAILED);
        }

        @Test
        void rejectsZero() {
            Payment payment = inStatus(AUTHORIZED);

            assertRejectedUnchanged(payment, () -> payment.capture(inr(0), LATER), VALIDATION_FAILED);
        }

        @Test
        void rejectsAnotherCurrency() {
            Payment payment = inStatus(AUTHORIZED);
            Money dollars = Money.ofMinorUnits(100, Currency.USD);

            assertRejectedUnchanged(payment, () -> payment.capture(dollars, LATER), CURRENCY_MISMATCH);
        }

        @Test
        void checksStateBeforeAmount() {
            Payment payment = inStatus(PENDING);

            assertRejectedUnchanged(payment, () -> payment.capture(inr(AMOUNT * 100), LATER),
                    INVALID_STATE_TRANSITION);
        }

        @Test
        void recordCaptureDeclineCountsAttemptAndStaysAuthorized() {
            Payment payment = inStatus(AUTHORIZED);

            payment.recordCaptureDecline(LATER);

            assertThat(payment.getStatus()).isEqualTo(AUTHORIZED);
            assertThat(payment.getCaptureAttempts()).isEqualTo(1);
            assertThat(payment.getUpdatedAt()).isEqualTo(LATER);
        }

        @Test
        void recordCaptureDeclineRejectedUnlessAuthorized() {
            Payment pending = inStatus(PENDING);
            assertRejectedUnchanged(pending, () -> pending.recordCaptureDecline(LATER), INVALID_STATE_TRANSITION);
            Payment captured = inStatus(CAPTURED);
            assertRejectedUnchanged(captured, () -> captured.recordCaptureDecline(LATER), INVALID_STATE_TRANSITION);
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancelsPendingPaymentWithoutReason() {
            Payment payment = inStatus(PENDING);

            payment.cancel(null, LATER);

            assertThat(payment.getStatus()).isEqualTo(CANCELLED);
            assertThat(payment.getCancellationReason()).isNull();
        }

        @Test
        void voidsAnAuthorizationAndKeepsTheReason() {
            Payment payment = inStatus(AUTHORIZED);

            payment.cancel("out of stock", LATER);

            assertThat(payment.getStatus()).isEqualTo(CANCELLED);
            assertThat(payment.getCancellationReason()).isEqualTo("out of stock");
            assertThat(payment.getCapturedAmount()).isEqualTo(inr(0));
        }

        @Test
        void cannotCancelOnceMoneyIsCaptured() {
            Payment payment = inStatus(CAPTURED);

            assertRejectedUnchanged(payment, () -> payment.cancel("too late", LATER), INVALID_STATE_TRANSITION);
        }
    }

    @Nested
    class Fail {

        @Test
        void failsPendingPaymentWithReason() {
            Payment payment = inStatus(PENDING);

            payment.fail("insufficient funds", LATER);

            assertThat(payment.getStatus()).isEqualTo(FAILED);
            assertThat(payment.getFailureReason()).isEqualTo("insufficient funds");
        }

        @Test
        void requiresAReason() {
            Payment payment = inStatus(PENDING);

            assertRejectedUnchanged(payment, () -> payment.fail("  ", LATER), VALIDATION_FAILED);
        }

        @Test
        void cannotFailAnAuthorizedPayment() {
            Payment payment = inStatus(AUTHORIZED);

            assertRejectedUnchanged(payment, () -> payment.fail("late decline", LATER), INVALID_STATE_TRANSITION);
        }
    }

    @Nested
    class Refund {

        @Test
        void refundsCapturedPaymentAndKeepsWhatWasCaptured() {
            Payment payment = inStatus(CAPTURED);
            Money captured = payment.getCapturedAmount();

            payment.refund(LATER);

            assertThat(payment.getStatus()).isEqualTo(REFUNDED);
            assertThat(payment.getCapturedAmount()).isEqualTo(captured);
        }

        @Test
        void cannotRefundBeforeCapture() {
            Payment payment = inStatus(AUTHORIZED);

            assertRejectedUnchanged(payment, () -> payment.refund(LATER), INVALID_STATE_TRANSITION);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED", "REFUNDED"})
    void terminalPaymentsRejectEveryCommand(PaymentStatus terminal) {
        Payment payment = inStatus(terminal);

        assertRejectedUnchanged(payment, () -> payment.authorize("psp-ref-1", LATER), INVALID_STATE_TRANSITION);
        assertRejectedUnchanged(payment, () -> payment.capture(LATER), INVALID_STATE_TRANSITION);
        assertRejectedUnchanged(payment, () -> payment.cancel(null, LATER), INVALID_STATE_TRANSITION);
        assertRejectedUnchanged(payment, () -> payment.fail("late decline", LATER), INVALID_STATE_TRANSITION);
        assertRejectedUnchanged(payment, () -> payment.refund(LATER), INVALID_STATE_TRANSITION);
    }

    @Nested
    class Rehydrate {

        @Test
        void rejectsCapturedAmountInAnotherCurrency() {
            assertThatThrownBy(() -> capturedWith(Money.ofMinorUnits(100, Currency.USD)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsCapturedAmountAboveTheAmount() {
            assertThatThrownBy(() -> capturedWith(inr(AMOUNT + 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private Payment capturedWith(Money captured) {
            return Payment.rehydrate(PaymentId.generate(), newOwner(), inr(AMOUNT), captured, CAPTURED,
                    null, null, null, null, null, 0, 1L, CREATED_AT, CREATED_AT);
        }
    }

    @Nested
    class Identity {

        @Test
        void equalityFollowsIdentityNotState() {
            Payment original = inStatus(PENDING);
            Payment sameIdElsewhereInTime = Payment.rehydrate(original.getId(), original.getOwner(),
                    original.getAmount(), original.getAmount(), CAPTURED,
                    null, null, null, null, "psp-ref", 0, 3L, CREATED_AT, LATER);

            assertThat(sameIdElsewhereInTime).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(inStatus(PENDING)).isNotEqualTo(original);
        }
    }

    private static void assertRejected(ThrowingCallable command, ErrorCode expected) {
        assertThatThrownBy(command).isInstanceOfSatisfying(PayFlowException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
    }

    private static void assertRejectedUnchanged(Payment payment, ThrowingCallable command, ErrorCode expected) {
        PaymentStatus statusBefore = payment.getStatus();
        Money capturedBefore = payment.getCapturedAmount();
        Instant updatedBefore = payment.getUpdatedAt();

        assertRejected(command, expected);

        assertThat(payment.getStatus()).isEqualTo(statusBefore);
        assertThat(payment.getCapturedAmount()).isEqualTo(capturedBefore);
        assertThat(payment.getUpdatedAt()).isEqualTo(updatedBefore);
    }
}

package com.payflow.payment.application.service;

import com.payflow.common.core.domain.Money;
import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.in.AuthorizePaymentUseCase;
import com.payflow.payment.application.port.in.CancelPaymentUseCase;
import com.payflow.payment.application.port.in.CapturePaymentUseCase;
import com.payflow.payment.application.port.in.CreatePaymentUseCase;
import com.payflow.payment.application.port.in.GetPaymentUseCase;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * One use-case call is one transaction. Every lookup goes through {@link #load}, which is
 * scoped to the caller's owner, so no command can reach another merchant's payment.
 */
@Service
// Default rollback rules on purpose: a lock conflict inside save() marks the transaction
// rollback-only, so committing after the resulting PayFlowException would surface as a 500.
@Transactional
public class PaymentApplicationService implements
        CreatePaymentUseCase,
        GetPaymentUseCase,
        AuthorizePaymentUseCase,
        CapturePaymentUseCase,
        CancelPaymentUseCase {

    private final PaymentRepositoryPort repository;
    private final Clock clock;

    public PaymentApplicationService(PaymentRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Payment create(CreatePaymentCommand command) {
        return repository.save(Payment.create(
                command.owner(), command.amount(), command.description(), command.reference(), clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Payment get(PaymentId id, PaymentOwner owner) {
        return load(id, owner);
    }

    @Override
    public Payment authorize(AuthorizePaymentCommand command) {
        return update(command.id(), command.owner(), payment -> payment.authorize(clock.instant()));
    }

    @Override
    public Payment capture(CapturePaymentCommand command) {
        return update(command.id(), command.owner(), payment -> {
            Instant now = clock.instant();
            if (command.amountMinor() == null) {
                payment.capture(now);
            } else {
                payment.capture(Money.ofMinorUnits(command.amountMinor(), payment.getAmount().currency()), now);
            }
        });
    }

    @Override
    public Payment cancel(CancelPaymentCommand command) {
        return update(command.id(), command.owner(), payment -> payment.cancel(command.reason(), clock.instant()));
    }

    private Payment update(PaymentId id, PaymentOwner owner, Consumer<Payment> change) {
        Payment payment = load(id, owner);
        change.accept(payment);
        return repository.save(payment);
    }

    private Payment load(PaymentId id, PaymentOwner owner) {
        return repository.findById(id, owner)
                .orElseThrow(() -> new PayFlowException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}

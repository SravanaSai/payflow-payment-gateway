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
import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.PaymentProcessor;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.application.port.out.ProcessorResult;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Authorize and capture call a {@link PaymentProcessor} outside any database transaction so
 * a slow PSP cannot hold connection-pool slots. Create, get, and cancel stay on the classic
 * transactional path from Phase 2.
 */
@Service
public class PaymentApplicationService implements
        CreatePaymentUseCase,
        GetPaymentUseCase,
        AuthorizePaymentUseCase,
        CapturePaymentUseCase,
        CancelPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentRepositoryPort repository;
    private final PaymentProcessor processor;
    private final Clock clock;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final TransactionTemplate writeTransactionTemplate;

    public PaymentApplicationService(
            PaymentRepositoryPort repository,
            PaymentProcessor processor,
            Clock clock,
            @Qualifier("readOnlyTransactionTemplate") TransactionTemplate readOnlyTransactionTemplate,
            @Qualifier("writeTransactionTemplate") TransactionTemplate writeTransactionTemplate) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
        this.readOnlyTransactionTemplate = readOnlyTransactionTemplate;
        this.writeTransactionTemplate = writeTransactionTemplate;
    }

    @Override
    @Transactional
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
        Instant now = clock.instant();
        Payment loaded = readOnlyTransactionTemplate.execute(
                status -> load(command.id(), command.owner()));
        loaded.checkAuthorizable();
        ProcessorResult result = processor.authorize(AuthorizationRequest.from(loaded));
        try {
            Payment saved = writeTransactionTemplate.execute(
                    status -> applyAuthorizeResult(loaded, result, now));
            if (result instanceof ProcessorResult.Declined declined) {
                throw new PayFlowException(ErrorCode.PAYMENT_DECLINED, declined.message());
            }
            return saved;
        } catch (PayFlowException ex) {
            if (ex.getErrorCode() == ErrorCode.CONCURRENT_MODIFICATION
                    && result instanceof ProcessorResult.Approved approved) {
                log.warn(
                        "Processor approved payment {} with reference {} but the save lost a concurrent update",
                        loaded.getId(),
                        approved.processorReference());
            }
            throw ex;
        }
    }

    @Override
    public Payment capture(CapturePaymentCommand command) {
        Instant now = clock.instant();
        Payment loaded = readOnlyTransactionTemplate.execute(
                status -> load(command.id(), command.owner()));
        Money captureAmount = resolveCaptureAmount(loaded, command.amountMinor());
        loaded.checkCapturable(captureAmount);
        ProcessorResult result = processor.capture(CaptureRequest.from(loaded, captureAmount));
        Payment saved = writeTransactionTemplate.execute(
                status -> applyCaptureResult(loaded, captureAmount, result, now));
        if (result instanceof ProcessorResult.Declined declined) {
            throw new PayFlowException(ErrorCode.PAYMENT_DECLINED, declined.message());
        }
        return saved;
    }

    @Override
    @Transactional
    public Payment cancel(CancelPaymentCommand command) {
        Payment payment = load(command.id(), command.owner());
        payment.cancel(command.reason(), clock.instant());
        return repository.save(payment);
    }

    private Payment applyAuthorizeResult(Payment payment, ProcessorResult result, Instant now) {
        return switch (result) {
            case ProcessorResult.Approved approved -> {
                payment.authorize(approved.processorReference(), now);
                yield repository.save(payment);
            }
            case ProcessorResult.Declined declined -> {
                payment.fail(declineFailureReason(declined), now);
                yield repository.save(payment);
            }
            case ProcessorResult.Unavailable unavailable ->
                    throw new PayFlowException(ErrorCode.SERVICE_UNAVAILABLE, unavailable.reason());
        };
    }

    private Payment applyCaptureResult(
            Payment payment, Money captureAmount, ProcessorResult result, Instant now) {
        return switch (result) {
            case ProcessorResult.Approved ignored -> {
                if (captureAmount.equals(payment.getAmount())) {
                    payment.capture(now);
                } else {
                    payment.capture(captureAmount, now);
                }
                yield repository.save(payment);
            }
            case ProcessorResult.Declined ignored -> {
                payment.recordCaptureDecline(now);
                yield repository.save(payment);
            }
            case ProcessorResult.Unavailable unavailable ->
                    throw new PayFlowException(ErrorCode.SERVICE_UNAVAILABLE, unavailable.reason());
        };
    }

    private static Money resolveCaptureAmount(Payment payment, Long amountMinor) {
        if (amountMinor == null) {
            return payment.getAmount();
        }
        return Money.ofMinorUnits(amountMinor, payment.getAmount().currency());
    }

    private static String declineFailureReason(ProcessorResult.Declined declined) {
        if (declined.message() != null && !declined.message().isBlank()) {
            return declined.message().trim();
        }
        return declined.code();
    }

    private Payment load(PaymentId id, PaymentOwner owner) {
        return repository.findById(id, owner)
                .orElseThrow(() -> new PayFlowException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}

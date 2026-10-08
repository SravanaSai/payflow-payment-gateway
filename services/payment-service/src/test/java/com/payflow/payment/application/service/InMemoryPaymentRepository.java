package com.payflow.payment.application.service;

import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.out.PaymentRepositoryPort;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentOwner;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Fake port for unit tests. Honours the real contract's owner scoping. */
final class InMemoryPaymentRepository implements PaymentRepositoryPort {

    private final Map<PaymentId, Payment> payments = new HashMap<>();

    @Override
    public Payment save(Payment payment) {
        payments.put(payment.getId(), payment);
        return payment;
    }

    @Override
    public Optional<Payment> findById(PaymentId id, PaymentOwner owner) {
        return Optional.ofNullable(payments.get(id)).filter(payment -> payment.getOwner().equals(owner));
    }
}

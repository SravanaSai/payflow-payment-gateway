package com.payflow.payment.adapter.out.processor;

import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.PaymentProcessor;
import com.payflow.payment.application.port.out.ProcessorResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default processor for local dev and Step 1 tests — always approves with a deterministic reference.
 */
@Component
@ConditionalOnProperty(name = "payflow.processor.type", havingValue = "in-memory", matchIfMissing = true)
class InMemoryPaymentProcessor implements PaymentProcessor {

    @Override
    public ProcessorResult authorize(AuthorizationRequest request) {
        return new ProcessorResult.Approved("mem_" + request.paymentId().value());
    }

    @Override
    public ProcessorResult capture(CaptureRequest request) {
        String reference = request.processorReference() != null
                ? request.processorReference()
                : "mem_" + request.paymentId().value();
        return new ProcessorResult.Approved(reference);
    }
}

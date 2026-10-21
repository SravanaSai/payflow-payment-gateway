package com.payflow.payment.application.port.out;

/**
 * Strategy port for payment processors (in-memory fake, HTTP mock-psp, real PSP adapters).
 */
public interface PaymentProcessor {

    ProcessorResult authorize(AuthorizationRequest request);

    ProcessorResult capture(CaptureRequest request);
}

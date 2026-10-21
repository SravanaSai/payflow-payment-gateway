package com.payflow.payment.adapter.out.processor.http;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.PaymentId;
import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.PaymentProcessor;
import com.payflow.payment.application.port.out.ProcessorResult;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.util.function.Supplier;

/** Calls mock-psp over HTTP with bulkhead → retry → circuit breaker → client timeouts. */
public class HttpPaymentProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentProcessor.class);

    private final PspClient client;
    private final Bulkhead bulkhead;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public HttpPaymentProcessor(PspClient client, Bulkhead bulkhead, Retry retry, CircuitBreaker circuitBreaker) {
        this.client = client;
        this.bulkhead = bulkhead;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ProcessorResult authorize(AuthorizationRequest request) {
        return call(request.paymentId(), () -> client.authorize(request));
    }

    @Override
    public ProcessorResult capture(CaptureRequest request) {
        if (request.processorReference() == null || request.processorReference().isBlank()) {
            log.warn("Payment {} has no processor reference; refusing capture", request.paymentId());
            throw new PayFlowException(
                    ErrorCode.PROCESSOR_ERROR,
                    "This payment has no processor reference and cannot be captured; cancel it instead");
        }
        return call(request.paymentId(), () -> client.capture(request));
    }

    private ProcessorResult call(PaymentId paymentId, Supplier<ProcessorResult> attempt) {
        Supplier<ProcessorResult> guarded = Bulkhead.decorateSupplier(
                bulkhead,
                Retry.decorateSupplier(
                        retry,
                        CircuitBreaker.decorateSupplier(circuitBreaker, attempt)));
        try {
            return guarded.get();
        } catch (PspRejectedException ex) {
            log.warn("Processor rejected payment {}: {}", paymentId, ex.getMessage());
            throw new PayFlowException(ErrorCode.PROCESSOR_ERROR, ErrorCode.PROCESSOR_ERROR.getDefaultMessage());
        } catch (CallNotPermittedException ex) {
            log.warn("Processor circuit open for payment {}", paymentId);
            return new ProcessorResult.Unavailable("The payment processor is temporarily unavailable");
        } catch (BulkheadFullException ex) {
            log.warn("Processor bulkhead full for payment {}", paymentId);
            return new ProcessorResult.Unavailable("The payment processor is busy, retry shortly");
        } catch (PspTransientException ex) {
            if (isTimeout(ex)) {
                log.warn("Processor timed out for payment {}", paymentId, ex);
                return new ProcessorResult.Unavailable("The payment processor did not respond in time");
            }
            log.warn("Processor unavailable for payment {}", paymentId, ex);
            return new ProcessorResult.Unavailable("The payment processor is unavailable");
        }
    }

    private static boolean isTimeout(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String simpleName = current.getClass().getSimpleName();
            if (simpleName.contains("Timeout")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }
}

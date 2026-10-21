package com.payflow.payment.application.service;

import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.PaymentProcessor;
import com.payflow.payment.application.port.out.ProcessorResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test double that returns queued outcomes and can block until a test releases it. */
public class ScriptedPaymentProcessor implements PaymentProcessor {

    private final Queue<ProcessorResult> authorizeResults = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessorResult> captureResults = new ConcurrentLinkedQueue<>();
    private final List<AuthorizationRequest> authorizeCalls = new ArrayList<>();
    private final List<CaptureRequest> captureCalls = new ArrayList<>();
    private volatile CountDownLatch authorizeBlock;

    public void reset() {
        authorizeResults.clear();
        captureResults.clear();
        authorizeCalls.clear();
        captureCalls.clear();
        authorizeBlock = null;
    }

    public void enqueueAuthorize(ProcessorResult... results) {
        authorizeResults.addAll(List.of(results));
    }

    public void enqueueCapture(ProcessorResult... results) {
        captureResults.addAll(List.of(results));
    }

    public void blockAuthorizeUntilReleased() {
        authorizeBlock = new CountDownLatch(1);
    }

    public void releaseAuthorizeBlock() {
        CountDownLatch latch = authorizeBlock;
        if (latch != null) {
            latch.countDown();
        }
    }

    public List<AuthorizationRequest> authorizeCalls() {
        return List.copyOf(authorizeCalls);
    }

    public List<CaptureRequest> captureCalls() {
        return List.copyOf(captureCalls);
    }

    @Override
    public ProcessorResult authorize(AuthorizationRequest request) {
        authorizeCalls.add(request);
        awaitAuthorizeBlock();
        ProcessorResult next = authorizeResults.poll();
        if (next != null) {
            return next;
        }
        return new ProcessorResult.Approved("script_" + request.paymentId().value());
    }

    @Override
    public ProcessorResult capture(CaptureRequest request) {
        captureCalls.add(request);
        ProcessorResult next = captureResults.poll();
        if (next != null) {
            return next;
        }
        return new ProcessorResult.Approved(request.processorReference());
    }

    private void awaitAuthorizeBlock() {
        CountDownLatch latch = authorizeBlock;
        if (latch == null) {
            return;
        }
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for authorize block release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while blocked on authorize", ex);
        }
    }
}

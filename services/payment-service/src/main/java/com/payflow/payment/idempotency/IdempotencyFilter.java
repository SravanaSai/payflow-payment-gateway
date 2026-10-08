package com.payflow.payment.idempotency;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.payment.adapter.in.web.GatewayIdentityFilter;
import com.payflow.payment.config.IdempotencyProperties;
import com.payflow.payment.domain.model.PaymentOwner;
import com.payflow.payment.idempotency.IdempotencyRecord.Status;
import com.payflow.payment.idempotency.IdempotencyRecord.StoredResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Makes every POST under {@code /v1/payments} safe to retry. The first request with a key claims
 * it and runs, and its response is stored; later requests with the same key replay that response
 * instead of running again. Runs after {@link GatewayIdentityFilter} because keys are scoped to
 * the caller's owner.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class IdempotencyFilter extends OncePerRequestFilter {

    static final String KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    enum Outcome {
        EXECUTED,
        REPLAYED,
        CONFLICT,
        IN_PROGRESS
    }

    private final IdempotencyStore store;
    private final int maxBodyBytes;
    private final HandlerExceptionResolver exceptionResolver;
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

    IdempotencyFilter(
            IdempotencyStore store,
            IdempotencyProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            MeterRegistry meters) {
        this.store = store;
        this.maxBodyBytes = properties.maxBodyBytes();
        this.exceptionResolver = exceptionResolver;
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, meters.counter("payflow.idempotency.requests",
                    "outcome", outcome.name().toLowerCase(Locale.ROOT)));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !request.getRequestURI().startsWith("/v1/payments");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        IdempotencyKey key;
        CachedBodyRequest buffered;
        try {
            PaymentOwner owner = GatewayIdentityFilter.ownerOf(request)
                    .orElseThrow(() -> new PayFlowException(ErrorCode.UNAUTHORIZED));
            key = new IdempotencyKey(owner, request.getHeader(KEY_HEADER));
            buffered = CachedBodyRequest.read(request, maxBodyBytes);
        } catch (PayFlowException ex) {
            reject(request, response, ex);
            return;
        }
        String fingerprint = RequestFingerprint.of(request.getMethod(), request.getRequestURI(), buffered.body());

        if (store.claim(key, fingerprint)) {
            outcomes.get(Outcome.EXECUTED).increment();
            execute(key, buffered, response, chain);
        } else {
            answerFromExistingRecord(key, fingerprint, request, response);
        }
    }

    private void execute(
            IdempotencyKey key,
            CachedBodyRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, captured);
        } catch (IOException | ServletException | RuntimeException ex) {
            releaseAfterFailure(key, ex);
            throw ex;
        }
        recordOutcome(key, captured);
        captured.copyBodyToResponse();
    }

    private void answerFromExistingRecord(
            IdempotencyKey key,
            String fingerprint,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        Optional<IdempotencyRecord> existing = store.find(key);
        if (existing.isEmpty()) {
            // Its owner released the key between our claim and this read; the client's retry will claim it.
            outcomes.get(Outcome.IN_PROGRESS).increment();
            reject(request, response, new PayFlowException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        } else if (!existing.get().fingerprint().equals(fingerprint)) {
            outcomes.get(Outcome.CONFLICT).increment();
            reject(request, response, new PayFlowException(ErrorCode.IDEMPOTENCY_CONFLICT));
        } else if (existing.get().status() == Status.IN_PROGRESS) {
            outcomes.get(Outcome.IN_PROGRESS).increment();
            reject(request, response, new PayFlowException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        } else {
            outcomes.get(Outcome.REPLAYED).increment();
            log.info("Replaying the stored response for {} {}", KEY_HEADER, key.value());
            replay(existing.get().response(), response);
        }
    }

    // Conflicts and server errors are transient, so they free the key for a retry. Every other
    // response below 500 is the operation's answer, and is replayed.
    static boolean isReplayable(int httpStatus) {
        return httpStatus < 500 && httpStatus != HttpStatus.CONFLICT.value();
    }

    private void recordOutcome(IdempotencyKey key, ContentCachingResponseWrapper captured) {
        try {
            if (isReplayable(captured.getStatus())) {
                store.complete(key, new StoredResponse(captured.getStatus(), captured.getContentType(),
                        captured.getHeader(HttpHeaders.LOCATION), captured.getContentAsByteArray()));
            } else {
                store.release(key);
            }
        } catch (RuntimeException ex) {
            // The client still gets the real response; the key stays IN_PROGRESS and fails safe until it expires.
            log.error("Could not record the outcome for {} {}", KEY_HEADER, key.value(), ex);
        }
    }

    private void releaseAfterFailure(IdempotencyKey key, Exception failure) {
        try {
            store.release(key);
        } catch (RuntimeException ex) {
            failure.addSuppressed(ex);
        }
    }

    private static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.httpStatus());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.location());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.body() != null) {
            response.getOutputStream().write(stored.body());
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, PayFlowException ex) {
        exceptionResolver.resolveException(request, response, null, ex);
    }
}

package com.payflow.gateway.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.web.PayFlowExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Maps proxy I/O failures (payment-service unreachable or too slow) to 503/504 before
 * {@link PayFlowExceptionHandler}'s catch-all turns them into 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UpstreamFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamFailureHandler.class);

    private final PayFlowExceptionHandler delegate;

    public UpstreamFailureHandler(PayFlowExceptionHandler delegate) {
        this.delegate = delegate;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> handleResourceAccess(ResourceAccessException ex) {
        ErrorCode code = classify(ex);
        log.warn("Upstream proxy failure: {}", ex.getMessage());
        return delegate.handlePayFlowException(new PayFlowException(code, code.getDefaultMessage(), ex));
    }

    private static ErrorCode classify(ResourceAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException
                    || isTimeoutClassName(cause)) {
                return ErrorCode.UPSTREAM_TIMEOUT;
            }
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return ErrorCode.SERVICE_UNAVAILABLE;
            }
            cause = cause.getCause();
        }
        return ErrorCode.SERVICE_UNAVAILABLE;
    }

    private static boolean isTimeoutClassName(Throwable cause) {
        String name = cause.getClass().getSimpleName();
        return name.contains("Timeout");
    }
}

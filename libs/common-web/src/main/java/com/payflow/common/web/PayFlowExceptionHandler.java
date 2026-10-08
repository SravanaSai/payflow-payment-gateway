package com.payflow.common.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Maps exceptions to RFC 7807 {@code application/problem+json} responses.
 * Every error includes a stable {@code errorCode} extension for programmatic clients.
 *
 * <p>Spring MVC's own client errors (bad path variable, unreadable body, wrong method) must be
 * handled by the inherited {@link ResponseEntityExceptionHandler} methods; if the catch-all
 * {@link #handleUnexpected} were their nearest match, they would all become 500s.
 */
@RestControllerAdvice
public class PayFlowExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI VALIDATION_TYPE = URI.create("https://payflow.dev/errors/validation");
    private static final URI DOMAIN_TYPE = URI.create("https://payflow.dev/errors/domain");

    @ExceptionHandler(PayFlowException.class)
    public ResponseEntity<ProblemDetail> handlePayFlowException(PayFlowException ex) {
        HttpStatus status = mapStatus(ex.getErrorCode());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(ex.getErrorCode().name());
        problem.setType(DOMAIN_TYPE);
        problem.setProperty("errorCode", ex.getErrorCode().name());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return builder.body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        problem.setTitle(ErrorCode.INTERNAL_ERROR.name());
        problem.setType(DOMAIN_TYPE);
        problem.setProperty("errorCode", ErrorCode.INTERNAL_ERROR.name());
        return ResponseEntity.internalServerError().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    // Every framework-handled error funnels through here, so this is the single place
    // that stamps the errorCode clients switch on.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            String errorCode = frameworkErrorCode(statusCode);
            problem.setTitle(errorCode);
            problem.setProperty("errorCode", errorCode);
            if (statusCode.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                problem.setType(VALIDATION_TYPE);
            }
        }
        return response;
    }

    private static String frameworkErrorCode(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.name() : "HTTP_" + status.value();
    }

    private HttpStatus mapStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case MERCHANT_NOT_FOUND, PAYMENT_NOT_FOUND, API_KEY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}

package com.payflow.mockpsp.web;

import com.payflow.mockpsp.web.dto.PspErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.stream.Collectors;

/**
 * Reports every error in mock-psp's own format. Spring's client errors (bad JSON, missing header,
 * wrong method, unknown path) go through the inherited handlers and are reshaped in one place;
 * without them the catch-all below would turn each of them into a 500.
 */
@RestControllerAdvice
public class PspErrorHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PspErrorHandler.class);

    @ExceptionHandler(PspException.class)
    public ResponseEntity<Object> handlePsp(PspException ex) {
        return ResponseEntity.status(ex.status())
                .body(PspErrorResponse.of(ex.type(), ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(PspErrorResponse.of("api_error", "internal_error", "An unexpected error occurred."));
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

    // Every framework-handled error funnels through here, so this is the one place
    // that converts Spring's problem detail into mock-psp's error shape.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        ResponseEntity<Object> base = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        String message = base != null && base.getBody() instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : "The request could not be processed.";
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(PspErrorResponse.of(typeFor(statusCode), codeFor(statusCode), message));
    }

    private static String typeFor(HttpStatusCode status) {
        return status.is5xxServerError() ? "api_error" : "invalid_request_error";
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "resource_missing";
            case 405 -> "method_not_allowed";
            case 415 -> "unsupported_media_type";
            default -> status.is5xxServerError() ? "internal_error" : "invalid_request";
        };
    }
}

package com.payflow.payment.adapter.out.processor.http;

import com.payflow.payment.adapter.out.processor.http.dto.PspAuthorizationResponse;
import com.payflow.payment.adapter.out.processor.http.dto.PspAuthorizeRequest;
import com.payflow.payment.adapter.out.processor.http.dto.PspCaptureRequest;
import com.payflow.payment.adapter.out.processor.http.dto.PspCaptureResponse;
import com.payflow.payment.adapter.out.processor.http.dto.PspErrorResponse;
import com.payflow.payment.application.port.out.AuthorizationRequest;
import com.payflow.payment.application.port.out.CaptureRequest;
import com.payflow.payment.application.port.out.ProcessorResult;
import com.payflow.common.web.CorrelationId;
import com.payflow.payment.config.ProcessorProperties;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;

/** One HTTP attempt to mock-psp — no retries here. */
public final class PspClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_IN_USE = "idempotency_key_in_use";

    private final RestClient restClient;
    private final JsonMapper json;

    private PspClient(RestClient restClient, JsonMapper json) {
        this.restClient = restClient;
        this.json = json;
    }

    public static PspClient create(RestClient.Builder builder, ProcessorProperties.Http http, JsonMapper json) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(http.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(http.readTimeout());

        RestClient client = builder
                .requestFactory(factory)
                .baseUrl(http.baseUrl().toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + http.apiKey())
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationId.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
        return new PspClient(client, json);
    }

    ProcessorResult authorize(AuthorizationRequest request) {
        PspAuthorizeRequest body = new PspAuthorizeRequest(
                request.amount().minorUnits(),
                request.amount().currency().name(),
                request.paymentId().value().toString());
        try {
            return restClient.post()
                    .uri("/v1/authorizations")
                    .header(IDEMPOTENCY_HEADER, request.outboundIdempotencyKey())
                    .body(body)
                    .exchange((req, response) -> mapAuthorize(response.getStatusCode(), response.bodyTo(String.class)));
        } catch (ResourceAccessException ex) {
            throw new PspTransientException("Payment processor network error", ex);
        }
    }

    ProcessorResult capture(CaptureRequest request) {
        PspCaptureRequest body = new PspCaptureRequest(request.amount().minorUnits());
        try {
            return restClient.post()
                    .uri("/v1/authorizations/{id}/captures", request.processorReference())
                    .header(IDEMPOTENCY_HEADER, request.outboundIdempotencyKey())
                    .body(body)
                    .exchange((req, response) -> mapCapture(response.getStatusCode(), response.bodyTo(String.class)));
        } catch (ResourceAccessException ex) {
            throw new PspTransientException("Payment processor network error", ex);
        }
    }

    private ProcessorResult mapAuthorize(HttpStatusCode status, String rawBody) {
        int code = status.value();
        if (code == 201) {
            return approvedFromAuthorization(readJson(rawBody, PspAuthorizationResponse.class, "authorize"));
        }
        if (code == 402) {
            return declined(readJson(rawBody, PspErrorResponse.class, "error"));
        }
        throw classifyFailure(code, rawBody);
    }

    private ProcessorResult mapCapture(HttpStatusCode status, String rawBody) {
        int code = status.value();
        if (code == 201) {
            return approvedFromCapture(readJson(rawBody, PspCaptureResponse.class, "capture"));
        }
        if (code == 402) {
            return declined(readJson(rawBody, PspErrorResponse.class, "error"));
        }
        throw classifyFailure(code, rawBody);
    }

    private static ProcessorResult approvedFromAuthorization(PspAuthorizationResponse body) {
        if (body.id() == null || body.id().isBlank()) {
            throw new PspRejectedException("Authorize response missing id");
        }
        return new ProcessorResult.Approved(body.id());
    }

    private static ProcessorResult approvedFromCapture(PspCaptureResponse body) {
        String reference = body.authorizationId();
        if (reference == null || reference.isBlank()) {
            throw new PspRejectedException("Capture response missing authorization_id");
        }
        return new ProcessorResult.Approved(reference);
    }

    private static ProcessorResult.Declined declined(PspErrorResponse error) {
        PspErrorResponse.Body body = error.error();
        if (body == null) {
            throw new PspRejectedException("Decline response missing error body");
        }
        String code = body.declineCode() != null && !body.declineCode().isBlank()
                ? body.declineCode()
                : body.code();
        String message = body.message() != null ? body.message() : "Declined";
        return new ProcessorResult.Declined(code, message);
    }

    private RuntimeException classifyFailure(int status, String rawBody) {
        if (status == 429 || status >= 500) {
            return new PspTransientException("Payment processor returned " + status);
        }
        if (status == 409 && isIdempotencyInUse(rawBody)) {
            return new PspTransientException("Payment processor idempotency key in use");
        }
        return new PspRejectedException("Payment processor returned status " + status);
    }

    private boolean isIdempotencyInUse(String rawBody) {
        try {
            PspErrorResponse parsed = readJson(rawBody, PspErrorResponse.class, "error");
            return parsed.error() != null && IDEMPOTENCY_IN_USE.equals(parsed.error().code());
        } catch (PspRejectedException ex) {
            return false;
        }
    }

    private <T> T readJson(String rawBody, Class<T> type, String label) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new PspRejectedException("Empty " + label + " response body");
        }
        try {
            return json.readValue(rawBody, type);
        } catch (RuntimeException ex) {
            throw new PspRejectedException("Unparseable " + label + " response body", ex);
        }
    }
}

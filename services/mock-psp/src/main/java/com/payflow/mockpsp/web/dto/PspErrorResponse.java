package com.payflow.mockpsp.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Every mock-psp error: {@code {"error": {"type": ..., "code": ..., "message": ...}}}. */
public record PspErrorResponse(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(
            String type,
            String code,
            @JsonProperty("decline_code") String declineCode,
            String message) {
    }

    public static PspErrorResponse of(String type, String code, String message) {
        return new PspErrorResponse(new Body(type, code, null, message));
    }

    public static PspErrorResponse declined(String declineCode, String message) {
        return new PspErrorResponse(new Body("card_error", "card_declined", declineCode, message));
    }
}

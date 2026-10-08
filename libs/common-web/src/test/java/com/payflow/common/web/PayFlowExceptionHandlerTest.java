package com.payflow.common.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayFlowExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new PayFlowExceptionHandler())
            .build();

    @Test
    void pathVariableOfTheWrongTypeIsAClientError() throws Exception {
        mockMvc.perform(get("/probe/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJsonIsAClientError() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownEnumValueIsAClientError() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1, \"level\": \"MEDIUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void beanValidationFailureNamesTheField() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 0, \"level\": \"LOW\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(containsString("amount")));
    }

    @Test
    void wrongHttpMethodIs405() throws Exception {
        mockMvc.perform(delete("/probe/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void payFlowExceptionKeepsItsOwnMapping() throws Exception {
        mockMvc.perform(get("/probe/domain-error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void genuinelyUnexpectedErrorIsStill500() throws Exception {
        mockMvc.perform(get("/probe/bug"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/{id}")
        String byId(@PathVariable("id") UUID id) {
            return id.toString();
        }

        @PostMapping("/probe")
        String create(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }

        @GetMapping("/probe/domain-error")
        String domainError() {
            throw new PayFlowException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        @GetMapping("/probe/bug")
        String bug() {
            throw new IllegalStateException("boom");
        }
    }

    record ProbeRequest(@NotNull @Positive Long amount, @NotNull Level level) {
    }

    enum Level {
        LOW,
        HIGH
    }
}

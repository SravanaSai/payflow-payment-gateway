package com.payflow.merchant.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.merchant.adapter.in.web.dto.CreateMerchantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createAndGetMerchant() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateMerchantRequest("Acme Corp", "ops@acme.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme Corp"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.apiKey.secret").exists())
                .andExpect(jsonPath("$.apiKey.environment").value("TEST"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        UUID id = UUID.fromString(
                objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/v1/merchants/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ops@acme.com"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test
    void getUnknownMerchantReturns404ProblemJson() throws Exception {
        mockMvc.perform(get("/v1/merchants/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MERCHANT_NOT_FOUND"));
    }
}

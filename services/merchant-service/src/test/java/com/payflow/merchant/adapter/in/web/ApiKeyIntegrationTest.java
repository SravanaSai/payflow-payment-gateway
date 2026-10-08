package com.payflow.merchant.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.common.security.ApiKeyEnvironment;
import com.payflow.common.security.HashedSecret;
import com.payflow.common.security.SecretHasher;
import com.payflow.merchant.adapter.in.web.dto.ApiKeyLookupRequest;
import com.payflow.merchant.adapter.in.web.dto.CreateMerchantRequest;
import com.payflow.merchant.adapter.in.web.dto.IssueApiKeyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SecretHasher secretHasher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void issueListLookupRotateAndRevoke() throws Exception {
        UUID merchantId = createMerchant();
        IssuedKey issued = issueApiKey(merchantId);

        mockMvc.perform(get("/v1/merchants/{merchantId}/api-keys", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].secret").doesNotExist());

        lookupSucceeds(merchantId, secretHasher.hash(issued.secret()));

        mockMvc.perform(post("/internal/v1/api-keys/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApiKeyLookupRequest(List.of(
                                new ApiKeyLookupRequest.HashCandidateDto(1, "not-a-valid-hash"))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("API_KEY_NOT_FOUND"));

        IssuedKey rotated = rotateKey(merchantId, issued.keyId());
        assertNotEquals(issued.secret(), rotated.secret());

        mockMvc.perform(get("/v1/merchants/{merchantId}/api-keys", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.expiresAt != null)]").exists());

        lookupSucceeds(merchantId, secretHasher.hash(rotated.secret()));

        mockMvc.perform(delete("/v1/merchants/{merchantId}/api-keys/{keyId}", merchantId, issued.keyId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/v1/merchants/{merchantId}/api-keys/{keyId}", merchantId, issued.keyId()))
                .andExpect(status().isNoContent());
    }

    private UUID createMerchant() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateMerchantRequest("Key Test Corp", "keys@test.com"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey.secret").exists())
                .andReturn();
        return UUID.fromString(
                objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private IssuedKey issueApiKey(UUID merchantId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/merchants/{merchantId}/api-keys", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new IssueApiKeyRequest(ApiKeyEnvironment.LIVE))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andExpect(jsonPath("$.keyId").exists())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String secret = body.get("secret").asText();
        String keyId = body.get("keyId").asText();
        assertNotNull(secret);
        return new IssuedKey(keyId, secret);
    }

    private IssuedKey rotateKey(UUID merchantId, String keyId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/v1/merchants/{merchantId}/api-keys/{keyId}/rotate", merchantId, keyId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new IssuedKey(body.get("keyId").asText(), body.get("secret").asText());
    }

    private void lookupSucceeds(UUID merchantId, HashedSecret hash) throws Exception {
        mockMvc.perform(post("/internal/v1/api-keys/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ApiKeyLookupRequest(List.of(
                                new ApiKeyLookupRequest.HashCandidateDto(
                                        hash.pepperVersion(), hash.toBase64()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.keyId").exists());
    }

    private record IssuedKey(String keyId, String secret) {
    }
}

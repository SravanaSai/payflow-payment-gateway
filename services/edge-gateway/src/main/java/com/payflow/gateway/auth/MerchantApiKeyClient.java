package com.payflow.gateway.auth;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.security.HashedSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * Calls merchant-service's internal lookup. The raw Bearer secret never leaves the gateway.
 */
@Component
public class MerchantApiKeyClient {

    private final RestClient restClient;

    public MerchantApiKeyClient(
            RestClient.Builder builder,
            @Value("${payflow.gateway.merchant-uri}") String merchantUri) {
        this.restClient = builder.clone().baseUrl(merchantUri).build();
    }

    public AuthenticatedMerchant lookup(List<HashedSecret> hashes) {
        ApiKeyLookupRequest body = new ApiKeyLookupRequest(hashes.stream()
                .map(hash -> new ApiKeyLookupRequest.HashCandidateDto(hash.pepperVersion(), hash.toBase64()))
                .toList());
        try {
            ApiKeyLookupResponse response = restClient.post()
                    .uri("/internal/v1/api-keys/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ApiKeyLookupResponse.class);
            if (response == null || response.merchantId() == null) {
                throw new PayFlowException(ErrorCode.UNAUTHORIZED);
            }
            return new AuthenticatedMerchant(response.merchantId(), response.keyId(), response.environment());
        } catch (PayFlowException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new PayFlowException(ErrorCode.UNAUTHORIZED);
            }
            throw new PayFlowException(ErrorCode.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        } catch (RestClientException ex) {
            throw new PayFlowException(ErrorCode.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }
}

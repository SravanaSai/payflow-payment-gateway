package com.payflow.payment.config;

import com.payflow.payment.adapter.out.processor.http.HttpPaymentProcessor;
import com.payflow.payment.adapter.out.processor.http.PspClient;
import com.payflow.payment.adapter.out.processor.http.PspResilience;
import com.payflow.payment.application.port.out.PaymentProcessor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@ConditionalOnProperty(name = "payflow.processor.type", havingValue = "http")
public class HttpProcessorConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpProcessorConfig.class);

    @Bean
    RetryConfigCustomizer pspRetryClassification() {
        return RetryConfigCustomizer.of(PspResilience.INSTANCE, PspResilience::classifyRetry);
    }

    @Bean
    CircuitBreakerConfigCustomizer pspBreakerClassification() {
        return CircuitBreakerConfigCustomizer.of(PspResilience.INSTANCE, PspResilience::classifyBreaker);
    }

    @Bean
    PspClient pspClient(RestClient.Builder restClientBuilder, ProcessorProperties properties, JsonMapper json) {
        ProcessorProperties.Http http = properties.http();
        if (http == null || http.baseUrl() == null) {
            throw new IllegalStateException("payflow.processor.http.base-url is required when payflow.processor.type=http");
        }
        if (http.apiKey() == null || http.apiKey().isBlank()) {
            throw new IllegalStateException("payflow.processor.http.api-key is required when payflow.processor.type=http");
        }
        return PspClient.create(restClientBuilder, http, json);
    }

    @Bean
    PaymentProcessor httpPaymentProcessor(
            PspClient pspClient,
            BulkheadRegistry bulkheadRegistry,
            RetryRegistry retryRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(PspResilience.INSTANCE);
        Retry retry = retryRegistry.retry(PspResilience.INSTANCE);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PspResilience.INSTANCE);

        retry.getEventPublisher().onRetry(event -> log.warn(
                "Retrying payment processor call, attempt {} after {}ms",
                event.getNumberOfRetryAttempts(),
                event.getWaitInterval().toMillis()));

        circuitBreaker.getEventPublisher().onStateTransition(event -> log.warn(
                "Payment processor circuit breaker {}",
                event.getStateTransition()));

        return new HttpPaymentProcessor(pspClient, bulkhead, retry, circuitBreaker);
    }
}

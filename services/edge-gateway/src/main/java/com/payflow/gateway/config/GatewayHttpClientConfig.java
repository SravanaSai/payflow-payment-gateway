package com.payflow.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Tomcat (merchant-service) and WireMock speak HTTP/1.1. The JDK HttpClient
 * defaults to HTTP/2, which WireMock cancels with RST_STREAM.
 *
 * <p>Uses the same {@code spring.http.clients.*} keys as Boot's RestClient builder
 * (merchant lookup). Keep read-timeout above payment-service's worst-case PSP budget (~4.2s).
 */
@Configuration
public class GatewayHttpClientConfig {

    @Bean
    JdkClientHttpRequestFactory gatewayRequestFactory(
            @Value("${spring.http.clients.connect-timeout:2s}") Duration connectTimeout,
            @Value("${spring.http.clients.read-timeout:5s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}

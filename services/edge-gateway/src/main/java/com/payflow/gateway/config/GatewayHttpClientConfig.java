package com.payflow.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Tomcat (merchant-service) and WireMock speak HTTP/1.1. The JDK HttpClient
 * defaults to HTTP/2, which WireMock cancels with RST_STREAM.
 */
@Configuration
public class GatewayHttpClientConfig {

    @Bean
    JdkClientHttpRequestFactory gatewayRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}

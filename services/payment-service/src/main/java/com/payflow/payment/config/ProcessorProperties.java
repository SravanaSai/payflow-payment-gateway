package com.payflow.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "payflow.processor")
public record ProcessorProperties(String type, Http http) {

    public record Http(URI baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
    }
}

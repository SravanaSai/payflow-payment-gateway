package com.payflow.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig {
}

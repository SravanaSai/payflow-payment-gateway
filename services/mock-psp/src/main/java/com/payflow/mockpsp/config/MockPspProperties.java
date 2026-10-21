package com.payflow.mockpsp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param apiKey        the Bearer key callers must present
 * @param slowDelay     how long the "slow" scenario waits before replying
 * @param flakyFailures how many attempts with one idempotency key fail before the "flaky" scenario approves
 */
@ConfigurationProperties(prefix = "mockpsp")
public record MockPspProperties(String apiKey, Duration slowDelay, int flakyFailures) {
}

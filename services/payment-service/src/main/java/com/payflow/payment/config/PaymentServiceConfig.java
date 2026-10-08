package com.payflow.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Configuration
public class PaymentServiceConfig {

    // Postgres stores microseconds; ticking at the same resolution keeps an
    // Instant identical before and after a round trip through the database.
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }
}

package com.payflow.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Starts WireMock servers standing in for merchant-service and payment-service before the
 * Spring context, so both downstream URIs are real ports, not {@code :0}.
 */
abstract class GatewayWireMockSupport {

    static final WireMockServer MERCHANT = new WireMockServer(wireMockConfig().dynamicPort());
    static final WireMockServer PAYMENT = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        MERCHANT.start();
        PAYMENT.start();
    }

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("payflow.gateway.merchant-uri", MERCHANT::baseUrl);
        registry.add("payflow.gateway.payment-uri", PAYMENT::baseUrl);
    }

    @BeforeEach
    void resetDownstreamStubs() {
        MERCHANT.resetAll();
        PAYMENT.resetAll();
    }
}

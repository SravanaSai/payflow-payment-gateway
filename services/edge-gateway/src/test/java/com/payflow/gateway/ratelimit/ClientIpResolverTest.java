package com.payflow.gateway.ratelimit;

import com.payflow.gateway.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void usesRemoteAddrByDefault() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustForwardedFor(false);
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.99");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void trustsLeftmostForwardedForWhenConfigured() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustForwardedFor(true);
        ClientIpResolver resolver = new ClientIpResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void masksIpv6To64Prefix() {
        String masked = ClientIpResolver.maskIpv6To64("2001:db8:85a3:8d3:1319:8a2e:0370:7348");
        assertThat(masked).isEqualTo("2001:0db8:85a3:08d3::/64");
    }
}

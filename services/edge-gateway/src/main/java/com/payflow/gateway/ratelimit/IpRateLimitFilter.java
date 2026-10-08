package com.payflow.gateway.ratelimit;

import com.payflow.gateway.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Pre-auth rate limit keyed by client IP. Protects expensive auth lookups and
 * public onboarding from floods before merchant identity exists.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IpRateLimitFilter extends AbstractRateLimitFilter {

    private final ClientIpResolver clientIpResolver;
    private final RateLimitProperties properties;

    public IpRateLimitFilter(
            TokenBucketLimiter limiter,
            ClientIpResolver clientIpResolver,
            RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        super(limiter, exceptionResolver);
        this.clientIpResolver = clientIpResolver;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        if (path.startsWith("/actuator")) {
            return true;
        }
        return !path.startsWith("/v1/");
    }

    @Override
    protected String resolveRedisKey(HttpServletRequest request) {
        return "rl:ip:" + clientIpResolver.resolve(request);
    }

    @Override
    protected RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        RateLimitProperties.TierProperties tier = properties.getAnonymous();
        return new RateLimitPolicy("anonymous", tier.getCapacity(), tier.getRefillPerSecond());
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.length() > 1 && uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }
}

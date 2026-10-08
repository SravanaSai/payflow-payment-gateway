package com.payflow.gateway.ratelimit;

import com.payflow.common.web.PayFlowHeaders;
import com.payflow.gateway.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Post-auth rate limit keyed by merchant and environment. Skipped when no
 * identity headers are present (e.g. public {@code POST /v1/merchants}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MerchantRateLimitFilter extends AbstractRateLimitFilter {

    private final RateLimitProperties properties;

    public MerchantRateLimitFilter(
            TokenBucketLimiter limiter,
            RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        super(limiter, exceptionResolver);
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(PayFlowHeaders.MERCHANT_ID) == null
                || request.getHeader(PayFlowHeaders.ENVIRONMENT) == null;
    }

    @Override
    protected String resolveRedisKey(HttpServletRequest request) {
        String merchantId = request.getHeader(PayFlowHeaders.MERCHANT_ID);
        String environment = request.getHeader(PayFlowHeaders.ENVIRONMENT);
        return "rl:mkt:" + merchantId + ":" + environment;
    }

    @Override
    protected RateLimitPolicy resolvePolicy(HttpServletRequest request) {
        String environment = request.getHeader(PayFlowHeaders.ENVIRONMENT);
        RateLimitProperties.TierProperties tier = "LIVE".equalsIgnoreCase(environment)
                ? properties.getLive()
                : properties.getTest();
        return new RateLimitPolicy(environment.toLowerCase(), tier.getCapacity(), tier.getRefillPerSecond());
    }
}

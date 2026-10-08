package com.payflow.gateway.ratelimit;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Shared rate-limit filter logic: check bucket, write headers, reject with 429.
 * Subclasses differ only in how they build the Redis key and pick the policy tier.
 */
abstract class AbstractRateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketLimiter limiter;
    private final HandlerExceptionResolver exceptionResolver;

    protected AbstractRateLimitFilter(
            TokenBucketLimiter limiter,
            HandlerExceptionResolver exceptionResolver) {
        this.limiter = limiter;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            RateLimitPolicy policy = resolvePolicy(request);
            RateLimitDecision decision = limiter.tryConsume(resolveRedisKey(request), policy);
            writeHeaders(response, decision);

            if (!decision.allowed()) {
                throw new PayFlowException(ErrorCode.RATE_LIMITED);
            }
            filterChain.doFilter(request, response);
        } catch (PayFlowException ex) {
            exceptionResolver.resolveException(request, response, null, ex);
        }
    }

    protected abstract String resolveRedisKey(HttpServletRequest request);

    protected abstract RateLimitPolicy resolvePolicy(HttpServletRequest request);

    private static void writeHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader(RateLimitHeaders.LIMIT, String.valueOf(decision.limit()));
        response.setHeader(RateLimitHeaders.REMAINING, String.valueOf(Math.max(0, decision.remaining())));
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            response.setHeader(RateLimitHeaders.RESET, String.valueOf(decision.retryAfterSeconds()));
        }
    }
}

package com.payflow.payment.adapter.in.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Optional;

/**
 * Parses the identity headers stamped by the edge gateway into a {@link PaymentOwner}, once.
 * Every {@code /v1/**} request without valid headers is rejected here, before a controller is
 * chosen, so no endpoint can be left unprotected by forgetting to ask for its owner.
 *
 * <p>This proves the headers are present and well-formed, not that they are genuine; that
 * guarantee comes from payment-service being reachable only through the gateway.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayIdentityFilter extends OncePerRequestFilter {

    static final String OWNER_ATTRIBUTE = GatewayIdentityFilter.class.getName() + ".owner";
    static final String MDC_MERCHANT_ID = "merchantId";
    static final String MDC_MERCHANT_ENVIRONMENT = "merchantEnvironment";

    private final HandlerExceptionResolver exceptionResolver;

    public GatewayIdentityFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    /** The owner this filter parsed for the current request; empty if the filter did not run. */
    public static Optional<PaymentOwner> ownerOf(ServletRequest request) {
        return request.getAttribute(OWNER_ATTRIBUTE) instanceof PaymentOwner owner
                ? Optional.of(owner)
                : Optional.empty();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        PaymentOwner owner;
        try {
            owner = parseOwner(request);
        } catch (PayFlowException ex) {
            exceptionResolver.resolveException(request, response, null, ex);
            return;
        }
        request.setAttribute(OWNER_ATTRIBUTE, owner);
        MDC.put(MDC_MERCHANT_ID, owner.merchantId().toString());
        MDC.put(MDC_MERCHANT_ENVIRONMENT, owner.environment().name());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_MERCHANT_ID);
            MDC.remove(MDC_MERCHANT_ENVIRONMENT);
        }
    }

    private static PaymentOwner parseOwner(HttpServletRequest request) {
        String merchantId = request.getHeader(PayFlowHeaders.MERCHANT_ID);
        String environment = request.getHeader(PayFlowHeaders.ENVIRONMENT);
        if (merchantId == null || environment == null) {
            throw new PayFlowException(ErrorCode.UNAUTHORIZED, "Missing gateway identity headers");
        }
        try {
            return new PaymentOwner(MerchantId.of(merchantId), PaymentEnvironment.valueOf(environment));
        } catch (IllegalArgumentException ex) {
            throw new PayFlowException(ErrorCode.UNAUTHORIZED, "Malformed gateway identity headers");
        }
    }
}

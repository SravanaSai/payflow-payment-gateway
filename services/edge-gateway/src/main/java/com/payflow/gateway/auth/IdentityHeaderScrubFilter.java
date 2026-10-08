package com.payflow.gateway.auth;

import com.payflow.common.web.PayFlowHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Removes every client-supplied {@code X-PayFlow-*} header before any other gateway check runs.
 * The merchant rate limiter and every downstream service treat those headers as identity the
 * gateway stamped, which only holds if a client can never send its own copy, on any route.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class IdentityHeaderScrubFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityHeaderScrubFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!hasIdentityHeader(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("Stripped client-supplied identity headers from {} {}", request.getMethod(), request.getRequestURI());
        filterChain.doFilter(new WithoutIdentityHeaders(request), response);
    }

    private static boolean hasIdentityHeader(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            if (PayFlowHeaders.isIdentityHeader(names.nextElement())) {
                return true;
            }
        }
        return false;
    }

    static final class WithoutIdentityHeaders extends HttpServletRequestWrapper {

        WithoutIdentityHeaders(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return PayFlowHeaders.isIdentityHeader(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return PayFlowHeaders.isIdentityHeader(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(PayFlowHeaders::isIdentityHeader);
            return Collections.enumeration(names);
        }
    }
}

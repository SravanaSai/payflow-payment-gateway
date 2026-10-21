package com.payflow.mockpsp.web;

import com.payflow.mockpsp.config.MockPspProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires {@code Authorization: Bearer <key>} on everything except the actuator endpoints,
 * the way a real payment company rejects calls that carry no valid secret.
 */
@Component
class ApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":{\"type\":\"authentication_error\",\"code\":\"invalid_api_key\","
                    + "\"message\":\"Provide a valid API key as a Bearer token.\"}}";

    private final byte[] expectedKey;

    ApiKeyFilter(MockPspProperties properties) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasValidKey(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    private boolean hasValidKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison: the time taken must not reveal how much of the key was right.
        return MessageDigest.isEqual(presented, expectedKey);
    }
}

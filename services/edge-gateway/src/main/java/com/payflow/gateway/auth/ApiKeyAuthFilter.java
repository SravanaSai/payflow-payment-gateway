package com.payflow.gateway.auth;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.common.security.SecretHasher;
import com.payflow.common.web.PayFlowHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates merchant API calls at the public edge.
 *
 * <p>Every {@code /v1/**} path requires {@code Authorization: Bearer} except the few listed in
 * {@code isPublic}: {@code POST /v1/merchants} must stay open because the caller has no key
 * until it returns. The raw secret is hashed here and looked up internally; downstream sees
 * identity headers only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Pattern MERCHANT_ID_IN_PATH =
            Pattern.compile("^/v1/merchants/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/|$)");

    private final SecretHasher secretHasher;
    private final MerchantApiKeyClient lookupClient;
    private final HandlerExceptionResolver exceptionResolver;

    public ApiKeyAuthFilter(
            SecretHasher secretHasher,
            MerchantApiKeyClient lookupClient,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.secretHasher = secretHasher;
        this.lookupClient = lookupClient;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !requiresAuthentication(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            AuthenticatedMerchant identity = authenticate(request);
            enforceMerchantScope(request, identity);
            filterChain.doFilter(new AuthenticatedRequest(request, identity), response);
        } catch (PayFlowException ex) {
            exceptionResolver.resolveException(request, response, null, ex);
        }
    }

    private AuthenticatedMerchant authenticate(HttpServletRequest request) {
        String secret = bearerSecret(request.getHeader(HttpHeaders.AUTHORIZATION));
        return lookupClient.lookup(secretHasher.hashAllVersions(secret));
    }

    private static String bearerSecret(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new PayFlowException(ErrorCode.UNAUTHORIZED);
        }
        String secret = authorization.substring(7).trim();
        if (secret.isEmpty()) {
            throw new PayFlowException(ErrorCode.UNAUTHORIZED);
        }
        return secret;
    }

    private static void enforceMerchantScope(HttpServletRequest request, AuthenticatedMerchant identity) {
        Matcher matcher = MERCHANT_ID_IN_PATH.matcher(path(request));
        if (matcher.find() && !identity.merchantId().toString().equalsIgnoreCase(matcher.group(1))) {
            throw new PayFlowException(ErrorCode.FORBIDDEN);
        }
    }

    // Default-deny: a new route is authenticated unless someone deliberately lists it in isPublic.
    static boolean requiresAuthentication(HttpServletRequest request) {
        String path = path(request);
        return path.startsWith("/v1/") && !isPublic(request.getMethod(), path);
    }

    private static boolean isPublic(String method, String path) {
        return "POST".equalsIgnoreCase(method) && "/v1/merchants".equals(path);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.length() > 1 && uri.endsWith("/")) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    /**
     * Strips {@code Authorization} and any client-supplied {@code X-PayFlow-*} headers,
     * then stamps the identity the lookup actually returned.
     */
    static final class AuthenticatedRequest extends HttpServletRequestWrapper {

        private final Map<String, String> stamped;

        AuthenticatedRequest(HttpServletRequest request, AuthenticatedMerchant identity) {
            super(request);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(PayFlowHeaders.MERCHANT_ID, identity.merchantId().toString());
            headers.put(PayFlowHeaders.KEY_ID, identity.keyId());
            headers.put(PayFlowHeaders.ENVIRONMENT, identity.environment().name());
            this.stamped = Map.copyOf(headers);
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return null;
            }
            String stampedValue = stampedValue(name);
            if (PayFlowHeaders.isIdentityHeader(name)) {
                return stampedValue;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            String stampedValue = stampedValue(name);
            if (PayFlowHeaders.isIdentityHeader(name)) {
                return stampedValue == null
                        ? Collections.emptyEnumeration()
                        : Collections.enumeration(List.of(stampedValue));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();
                if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) || PayFlowHeaders.isIdentityHeader(name)) {
                    continue;
                }
                names.add(name);
            }
            names.addAll(stamped.keySet());
            return Collections.enumeration(names);
        }

        private String stampedValue(String name) {
            for (Map.Entry<String, String> entry : stamped.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}

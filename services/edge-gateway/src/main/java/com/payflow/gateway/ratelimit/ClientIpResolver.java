package com.payflow.gateway.ratelimit;

import com.payflow.gateway.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a stable client IP string for IP-based rate limiting.
 * Defaults to the direct TCP peer; optionally trusts {@code X-Forwarded-For}
 * when the gateway sits behind a known load balancer.
 */
@Component
@ConditionalOnProperty(prefix = "payflow.gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private final RateLimitProperties properties;

    public ClientIpResolver(RateLimitProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String ip = properties.isTrustForwardedFor()
                ? resolveForwarded(request)
                : request.getRemoteAddr();
        return normalize(ip);
    }

    private String resolveForwarded(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader(X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String normalize(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) {
                ip = ip.substring(1, end);
            }
        }
        int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        if (ip.contains(":")) {
            return maskIpv6To64(ip);
        }
        return ip;
    }

    static String maskIpv6To64(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (!(address instanceof Inet6Address)) {
                return ip;
            }
            byte[] bytes = address.getAddress();
            return String.format(
                    "%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64",
                    bytes[0], bytes[1], bytes[2], bytes[3],
                    bytes[4], bytes[5], bytes[6], bytes[7]);
        } catch (UnknownHostException ex) {
            return ip;
        }
    }
}

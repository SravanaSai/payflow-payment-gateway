package com.payflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit tiers for the public edge. IP (anonymous) limits run before auth;
 * merchant limits run after identity is resolved.
 */
@ConfigurationProperties(prefix = "payflow.gateway.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private boolean trustForwardedFor = false;
    private TierProperties anonymous = new TierProperties(100, 20);
    private TierProperties live = new TierProperties(100, 50);
    private TierProperties test = new TierProperties(20, 10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public TierProperties getAnonymous() {
        return anonymous;
    }

    public void setAnonymous(TierProperties anonymous) {
        this.anonymous = anonymous;
    }

    public TierProperties getLive() {
        return live;
    }

    public void setLive(TierProperties live) {
        this.live = live;
    }

    public TierProperties getTest() {
        return test;
    }

    public void setTest(TierProperties test) {
        this.test = test;
    }

    public static class TierProperties {

        private int capacity;
        private double refillPerSecond;

        public TierProperties() {
        }

        public TierProperties(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }
}

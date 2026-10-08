package com.payflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Same pepper binding as merchant-service. Both processes must share
 * {@code PAYFLOW_API_KEY_PEPPER} or every lookup misses and every request is 401.
 */
@ConfigurationProperties(prefix = "payflow.security")
public class SecurityProperties {

    private PepperProperties pepper = new PepperProperties();

    public PepperProperties getPepper() {
        return pepper;
    }

    public void setPepper(PepperProperties pepper) {
        this.pepper = pepper;
    }

    public static class PepperProperties {

        private int currentVersion = 1;
        private Map<String, String> values = new LinkedHashMap<>();

        public int getCurrentVersion() {
            return currentVersion;
        }

        public void setCurrentVersion(int currentVersion) {
            this.currentVersion = currentVersion;
        }

        public Map<String, String> getValues() {
            return values;
        }

        public void setValues(Map<String, String> values) {
            this.values = values;
        }
    }
}

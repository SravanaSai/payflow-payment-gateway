package com.payflow.merchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds pepper material from configuration. The pepper must never be stored in the
 * database — only in environment variables (locally) or a secrets manager (prod).
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

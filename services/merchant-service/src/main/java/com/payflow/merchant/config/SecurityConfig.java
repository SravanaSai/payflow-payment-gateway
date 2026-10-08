package com.payflow.merchant.config;

import com.payflow.common.security.ApiKeyGenerator;
import com.payflow.common.security.Pepper;
import com.payflow.common.security.PepperRegistry;
import com.payflow.common.security.SecretHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires {@link common-security} primitives as Spring beans.
 * If the pepper is missing or too short, {@link Pepper#fromBase64} fails here and
 * the service refuses to start rather than failing on the first auth request.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public PepperRegistry pepperRegistry(SecurityProperties properties) {
        SecurityProperties.PepperProperties pepperProps = properties.getPepper();
        List<Pepper> peppers = new ArrayList<>();
        pepperProps.getValues().forEach((versionKey, base64Material) -> {
            int version = Integer.parseInt(versionKey);
            peppers.add(Pepper.fromBase64(version, base64Material));
        });
        return PepperRegistry.of(peppers, pepperProps.getCurrentVersion());
    }

    @Bean
    public SecretHasher secretHasher(PepperRegistry pepperRegistry) {
        return new SecretHasher(pepperRegistry);
    }

    @Bean
    public ApiKeyGenerator apiKeyGenerator() {
        return new ApiKeyGenerator();
    }
}

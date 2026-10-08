package com.payflow.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-registers correlation filter, RFC 7807 handler, and OpenAPI when a service
 * adds {@code com.payflow:common-web} to its classpath.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = {
        CorrelationIdFilter.class,
        PayFlowExceptionHandler.class,
        OpenApiConfig.class
})
public class PayFlowWebAutoConfiguration {
}

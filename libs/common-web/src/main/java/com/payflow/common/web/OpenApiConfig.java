package com.payflow.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Base OpenAPI metadata — services override title/description via properties. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI payFlowOpenApi(
            @Value("${payflow.openapi.title:PayFlow API}") String title,
            @Value("${payflow.openapi.description:PayFlow payment platform}") String description,
            @Value("${payflow.openapi.version:v1}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .description(description)
                        .version(version)
                        .contact(new Contact().name("PayFlow").email("dev@payflow.dev"))
                        .license(new License().name("Apache 2.0")));
    }
}

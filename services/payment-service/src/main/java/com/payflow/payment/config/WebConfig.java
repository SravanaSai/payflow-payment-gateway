package com.payflow.payment.config;

import com.payflow.payment.adapter.in.web.PaymentOwnerArgumentResolver;
import com.payflow.payment.domain.model.PaymentOwner;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // PaymentOwner comes from gateway headers, not the request; without this springdoc
    // would document its fields as query parameters.
    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(PaymentOwner.class);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PaymentOwnerArgumentResolver());
    }
}

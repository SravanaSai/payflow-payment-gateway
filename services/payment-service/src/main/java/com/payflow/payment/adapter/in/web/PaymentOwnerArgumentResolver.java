package com.payflow.payment.adapter.in.web;

import com.payflow.common.core.error.ErrorCode;
import com.payflow.common.core.error.PayFlowException;
import com.payflow.payment.domain.model.PaymentOwner;
import jakarta.servlet.ServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/** Supplies any {@link PaymentOwner} controller parameter with the owner {@link GatewayIdentityFilter} parsed. */
public class PaymentOwnerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PaymentOwner.class;
    }

    @Override
    public PaymentOwner resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return Optional.ofNullable(webRequest.getNativeRequest(ServletRequest.class))
                .flatMap(GatewayIdentityFilter::ownerOf)
                .orElseThrow(() -> new PayFlowException(ErrorCode.UNAUTHORIZED));
    }
}

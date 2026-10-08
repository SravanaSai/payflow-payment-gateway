package com.payflow.payment.adapter.in.web;

import com.payflow.common.core.ids.MerchantId;
import com.payflow.common.web.PayFlowHeaders;
import com.payflow.payment.domain.model.PaymentEnvironment;
import com.payflow.payment.domain.model.PaymentOwner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayIdentityFilterTest {

    private final GatewayIdentityFilter filter = new GatewayIdentityFilter((request, response, handler, ex) -> {
        throw new AssertionError("valid identity was rejected", ex);
    });

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesTheOwnerAndTagsLogsOnlyForTheDurationOfTheRequest() throws Exception {
        String merchantId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/payments/" + UUID.randomUUID());
        request.addHeader(PayFlowHeaders.MERCHANT_ID, merchantId);
        request.addHeader(PayFlowHeaders.ENVIRONMENT, "LIVE");
        Map<String, Object> seenDownstream = new HashMap<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seenDownstream.put("owner", req.getAttribute(GatewayIdentityFilter.OWNER_ATTRIBUTE));
            seenDownstream.put("merchantId", MDC.get(GatewayIdentityFilter.MDC_MERCHANT_ID));
            seenDownstream.put("environment", MDC.get(GatewayIdentityFilter.MDC_MERCHANT_ENVIRONMENT));
        });

        assertThat(seenDownstream.get("owner"))
                .isEqualTo(new PaymentOwner(MerchantId.of(merchantId), PaymentEnvironment.LIVE));
        assertThat(seenDownstream.get("merchantId")).isEqualTo(merchantId);
        assertThat(seenDownstream.get("environment")).isEqualTo("LIVE");
        assertThat(MDC.get(GatewayIdentityFilter.MDC_MERCHANT_ID)).isNull();
        assertThat(MDC.get(GatewayIdentityFilter.MDC_MERCHANT_ENVIRONMENT)).isNull();
    }
}

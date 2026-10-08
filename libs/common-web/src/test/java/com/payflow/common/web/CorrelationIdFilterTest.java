package com.payflow.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesIdWhenHeaderMissingAndExposesItOnTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Holder holder = new Holder();

        filter.doFilter(request, response, capturingChain(holder));

        assertThat(holder.header).isNotBlank();
        assertThat(holder.headerNames).contains(CorrelationId.HEADER);
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(holder.header);
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void preservesClientSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "client-trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Holder holder = new Holder();

        filter.doFilter(request, response, capturingChain(holder));

        assertThat(holder.header).isEqualTo("client-trace-1");
        assertThat(holder.headers).containsExactly("client-trace-1");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("client-trace-1");
    }

    private static FilterChain capturingChain(Holder holder) {
        return (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            holder.header = http.getHeader(CorrelationId.HEADER);
            holder.headers = Collections.list(http.getHeaders(CorrelationId.HEADER));
            holder.headerNames = Collections.list(http.getHeaderNames());
        };
    }

    private static final class Holder {
        String header;
        List<String> headers;
        List<String> headerNames;
    }
}

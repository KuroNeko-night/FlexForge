package com.flexforge.app.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * requestId 过滤器单元回归：生成/透传/重生成、响应头与 MDC 清理。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String filterResponseHeader(String incomingHeader,
                                        AtomicReference<String> mdcDuringRequest) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        if (incomingHeader != null) {
            request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, incomingHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> mdcDuringRequest.set(MDC.get(RequestIdFilter.MDC_KEY)));
        return response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
    }

    @Test
    void generatesRequestIdWhenHeaderAbsentAndClearsMdc() throws Exception {
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        String header = filterResponseHeader(null, mdcDuringRequest);

        assertThat(header).matches("req-[0-9a-f]{32}");
        assertThat(mdcDuringRequest.get()).isEqualTo(header);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("请求结束后 MDC 必须清理").isNull();
    }

    @Test
    void propagatesWellFormedIncomingRequestId() throws Exception {
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        String header = filterResponseHeader("req-0123456789abcdef", mdcDuringRequest);

        assertThat(header).isEqualTo("req-0123456789abcdef");
        assertThat(mdcDuringRequest.get()).isEqualTo(header);
    }

    @Test
    void regeneratesMalformedIncomingRequestId() throws Exception {
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        String header = filterResponseHeader("req-EVIL", mdcDuringRequest);

        assertThat(header).isNotEqualTo("req-EVIL").matches("req-[0-9a-f]{8,64}");
    }
}

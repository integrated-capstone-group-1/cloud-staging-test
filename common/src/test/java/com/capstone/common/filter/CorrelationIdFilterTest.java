package com.capstone.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final FilterChain filterChain = mock(FilterChain.class);

    @Test
    @DisplayName("DIG-63: CorrelationIdFilter propagates incoming X-Correlation-ID header and sets MDC")
    void testPropagateCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader("X-Correlation-ID", "corr-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doAnswer(invocation -> {
            assertThat(MDC.get("transactionId")).isEqualTo("corr-12345");
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("corr-12345");
        assertThat(response.getHeader("X-Transaction-ID")).isEqualTo("corr-12345");
        // MDC must be cleaned up after request
        assertThat(MDC.get("transactionId")).isNull();
    }

    @Test
    @DisplayName("DIG-63: CorrelationIdFilter generates new UUID if header is missing")
    void testGenerateCorrelationIdWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        String generatedId = response.getHeader("X-Correlation-ID");
        assertThat(generatedId).isNotBlank();
        assertThat(response.getHeader("X-Transaction-ID")).isEqualTo(generatedId);
    }
}


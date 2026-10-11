package com.capstone.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

    @Mock
    private GatewayFilterChain chain;

    private SecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
    }

    @Test
    @DisplayName("REG-RUS-01: Inbound Header Sanitization strips spoofed X-Cust-Id and X-Cust-Role headers")
    void shouldStripUntrustedCustomerHeaders() {
        MockServerHttpRequest incomingRequest = MockServerHttpRequest.post("/api/v1/payments")
                .header("X-Cust-Id", "cust-admin-001")
                .header("X-Cust-Role", "ROLE_ADMIN")
                .header("X-Cust-Email", "hacker@evil.com")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer legitimate-token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(incomingRequest);
        when(chain.filter(org.mockito.ArgumentMatchers.any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());

        ServerWebExchange forwardedExchange = captor.getValue();
        assertThat(forwardedExchange.getRequest().getHeaders().containsKey("X-Cust-Id")).isFalse();
        assertThat(forwardedExchange.getRequest().getHeaders().containsKey("X-Cust-Role")).isFalse();
        assertThat(forwardedExchange.getRequest().getHeaders().containsKey("X-Cust-Email")).isFalse();
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer legitimate-token");
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("REG-RUS-01: Passes request unchanged when no untrusted headers are present")
    void shouldPassCleanRequestUnchanged() {
        MockServerHttpRequest incomingRequest = MockServerHttpRequest.get("/api/accounts/123")
                .header("Authorization", "Bearer token-123")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(incomingRequest);
        when(chain.filter(org.mockito.ArgumentMatchers.any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());

        ServerWebExchange forwardedExchange = captor.getValue();
        assertThat(forwardedExchange.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer token-123");
    }
}


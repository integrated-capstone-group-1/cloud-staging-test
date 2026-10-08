package com.capstone.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayRouteIsolationTest {

    @Mock
    private GatewayFilterChain chain;

    private GatewayRouteIsolationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GatewayRouteIsolationFilter();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/core/settlement",
            "/internal/accounts/acc-01/holds",
            "/internal/risk/evaluate",
            "/api/accounts/acc-101/credit",
            "/api/accounts/acc-101/debit",
            "/api/accounts/credit",
            "/api/accounts/debit"
    })
    @DisplayName("REG-RUS-02: Blocks external requests to cloaked internal mutation routes with 403 Forbidden")
    void shouldBlockInternalRoutes(String blockedPath) {
        MockServerHttpRequest request = MockServerHttpRequest.post(blockedPath).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Route-Error")).contains("ACCESS_DENIED");
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/payments",
            "/api/v1/accounts/acc-101/pockets",
            "/api/v1/dashboard/home",
            "/api/accounts/acc-101",
            "/api/auth/login"
    })
    @DisplayName("REG-RUS-02: Allows legitimate external customer routes through filter chain")
    void shouldAllowLegitimateCustomerRoutes(String allowedPath) {
        MockServerHttpRequest request = MockServerHttpRequest.get(allowedPath).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        verify(chain).filter(any(ServerWebExchange.class));
    }
}


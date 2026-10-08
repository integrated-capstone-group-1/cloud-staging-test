package com.capstone.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression Test Suite: REG-GYM-03 (DIG-26).
 * Verifies Redis Token-Bucket Rate Limiter Quota Replenishment:
 * - 15 req/s replenishment
 * - Burst capacity: 30
 * - Burst 35 requests: 1-30 succeed, 31-35 rejected with HTTP 429 and Retry-After
 * - After 1 second pause/replenishment, subsequent request succeeds.
 */
class RateLimiterFilterTest {

    private RateLimiterFilter rateLimiterFilter;
    private GatewayFilterChain chain;
    private SettableTestClock testClock;

    @BeforeEach
    void setUp() {
        testClock = new SettableTestClock(Instant.now());
        // Configure standard rate limiter: 15 req/s replenish, 30 burst capacity
        rateLimiterFilter = new RateLimiterFilter(null, 15, 30, testClock);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("REG-GYM-03: Burst 35 requests: 1-30 succeed, 31-35 return 429 with Retry-After; replenishes after 1s")
    void shouldRejectExcessRequestsAndReplenish() {
        String clientIp = "192.168.1.100";

        // Step 1: Burst 30 requests - all 30 should consume tokens and succeed
        for (int i = 1; i <= 30; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                    .header("X-Forwarded-For", clientIp)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(rateLimiterFilter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("Request %d should be accepted within burst quota", i)
                    .isNull(); // Not set to 429, chain was executed
        }

        // Verify chain was invoked exactly 30 times
        verify(chain, times(30)).filter(any());

        // Step 2: Requests 31 to 35 within the same millisecond must return HTTP 429
        for (int i = 31; i <= 35; i++) {
            MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                    .header("X-Forwarded-For", clientIp)
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(rateLimiterFilter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("Request %d must be rejected with 429 TOO_MANY_REQUESTS", i)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                    .isEqualTo("1");
        }

        // Verify chain was NOT invoked for the 5 rate-limited requests
        verify(chain, times(30)).filter(any());

        // Step 3: Advance clock by 1 second (1000 ms) -> Replenishes 15 tokens
        testClock.plusSeconds(1);

        MockServerHttpRequest subsequentRequest = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                .header("X-Forwarded-For", clientIp)
                .build();
        MockServerWebExchange subsequentExchange = MockServerWebExchange.from(subsequentRequest);

        StepVerifier.create(rateLimiterFilter.filter(subsequentExchange, chain))
                .verifyComplete();

        // Must succeed now
        assertThat(subsequentExchange.getResponse().getStatusCode()).isNull();
        verify(chain, times(31)).filter(any());
    }

    /**
     * Deterministic controllable test clock for microsecond/second advancements.
     */
    private static class SettableTestClock extends Clock {
        private final AtomicLong currentMillis;
        private final ZoneId zoneId = ZoneId.of("UTC");

        public SettableTestClock(Instant initialInstant) {
            this.currentMillis = new AtomicLong(initialInstant.toEpochMilli());
        }

        public void plusSeconds(long seconds) {
            this.currentMillis.addAndGet(seconds * 1000);
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(currentMillis.get());
        }

        @Override
        public long millis() {
            return currentMillis.get();
        }
    }
}


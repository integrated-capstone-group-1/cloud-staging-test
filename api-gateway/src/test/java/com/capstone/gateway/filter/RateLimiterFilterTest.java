package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RUS-03: Rate Limiter Enforcement Tests (15 req/s Quota)
 *
 * [W-H-Y MODEL & TRACEABILITY]:
 * - WHAT: Validate that the API Gateway rate limiter enforces 15 req/s quota per client.
 * - HOW: Unit tests for RateLimiterFilter using token bucket algorithm
 * - WHY: Protects downstream services against DDoS, scraping, and runaway loops (DIG-26, DIG-8)
 *
 * Token-Bucket Algorithm:
 * - Initial capacity: 30 tokens (burst)
 * - Replenishment rate: 15 tokens/second
 * - Each request costs 1 token
 *
 * Jira: DIG-8, DIG-26
 */
@Slf4j
class RateLimiterFilterTest {

    private RateLimiterFilter rateLimiterFilter;
    private SettableTestClock testClock;

    @BeforeEach
    void setUp() {
        testClock = new SettableTestClock(Instant.now());
        rateLimiterFilter = new RateLimiterFilter(null, 15, 30, testClock);
    }

    // ============================================================================
    // TOKEN BUCKET CORE FUNCTIONALITY TESTS
    // ============================================================================

    @Test
    @DisplayName("RATE-01: First 15 requests succeed (quota available)")
    void testFirst15RequestsSucceed() {
        int countAllowed = 0;

        for (int i = 0; i < 15; i++) {
            Boolean allowed = rateLimiterFilter.tryAcquireToken("client-192.168.1.100").block();
            if (allowed) countAllowed++;
        }

        assertThat(countAllowed).isEqualTo(15);
    }

    @Test
    @DisplayName("RATE-02: Burst capacity allows 30 tokens")
    void testBurstCapacity30() {
        int countAllowed = 0;

        for (int i = 0; i < 30; i++) {
            Boolean allowed = rateLimiterFilter.tryAcquireToken("client-burst-300").block();
            if (allowed) countAllowed++;
        }

        assertThat(countAllowed).isEqualTo(30);
    }

    @Test
    @DisplayName("RATE-03: Filter order is HIGHEST_PRECEDENCE + 20")
    void testFilterOrder() {
        assertThat(rateLimiterFilter.getOrder()).isEqualTo(Integer.MIN_VALUE + 20);
    }

    @Test
    @DisplayName("RATE-04: Rate limited request returns 429 Too Many Requests")
    void testHttp429Response() {
        GatewayFilterChain chain = exchange -> Mono.empty();

        // Exhaust quota (30 tokens - burst capacity)
        for (int i = 0; i < 30; i++) {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/accounts")
                    .header("X-Forwarded-For", "192.168.1.999")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            rateLimiterFilter.filter(exchange, chain).block();
        }

        // Next request should get 429
        MockServerHttpRequest requestOver = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Forwarded-For", "192.168.1.999")
                .build();
        MockServerWebExchange exchangeOver = MockServerWebExchange.from(requestOver);
        rateLimiterFilter.filter(exchangeOver, chain).block();

        assertThat(exchangeOver.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("RATE-05: Rate limit response includes Retry-After header")
    void testRetryAfterHeader() {
        GatewayFilterChain chain = exchange -> Mono.empty();

        // Exhaust burst capacity (30)
        for (int i = 0; i < 30; i++) {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/accounts")
                    .header("X-Forwarded-For", "192.168.2.999")
                    .build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);
            rateLimiterFilter.filter(exchange, chain).block();
        }

        // Rate limited request
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Forwarded-For", "192.168.2.999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        rateLimiterFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("RATE-06: Replenishment configured at 15 tokens/sec")
    void testReplenishmentRate() {
        // Verify replenishment rate is 15 tokens/sec
        String clientKey = "client-replenish-600";

        // Consume burst (30 tokens)
        for (int i = 0; i < 30; i++) {
            rateLimiterFilter.tryAcquireToken(clientKey).block();
        }

        // Advance 1 second (should replenish 15 tokens)
        testClock.plusSeconds(1);

        // Should be able to get 15 tokens
        int countAllowed = 0;
        for (int i = 0; i < 15; i++) {
            Boolean allowed = rateLimiterFilter.tryAcquireToken(clientKey).block();
            if (allowed) countAllowed++;
        }

        assertThat(countAllowed).isEqualTo(15);
    }

    @Test
    @DisplayName("RATE-07: Tokens capped at burst capacity (max 30)")
    void testTokensCapAtBurst() {
        String clientKey = "client-cap-700";

        // Use 10 tokens
        for (int i = 0; i < 10; i++) {
            rateLimiterFilter.tryAcquireToken(clientKey).block();
        }

        // Advance 10 seconds (would replenish 150 if uncapped, but should cap at 30)
        testClock.plusSeconds(10);

        // Count how many tokens are available
        int countAllowed = 0;
        for (int i = 0; i < 35; i++) {
            Boolean allowed = rateLimiterFilter.tryAcquireToken(clientKey).block();
            if (allowed) countAllowed++;
        }

        // Should not exceed burst capacity of 30
        assertThat(countAllowed).isLessThanOrEqualTo(30);
    }

    /**
     * Test utility: Settable clock for controlling time in tests.
     */
    private static class SettableTestClock extends Clock {
        private Instant instant;

        public SettableTestClock(Instant initial) {
            this.instant = initial;
        }

        public void plusSeconds(double seconds) {
            long nanos = (long) (seconds * 1_000_000_000L);
            this.instant = instant.plusNanos(nanos);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public long millis() {
            return instant.toEpochMilli();
        }
    }
}

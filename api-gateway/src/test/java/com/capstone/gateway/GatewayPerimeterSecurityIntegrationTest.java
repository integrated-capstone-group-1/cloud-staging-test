package com.capstone.gateway;

import com.capstone.gateway.filter.GatewayRouteIsolationFilter;
import com.capstone.gateway.filter.JwtAuthenticationGatewayFilterFactory;
import com.capstone.gateway.filter.RateLimiterFilter;
import com.capstone.gateway.filter.SecurityHeadersFilter;
import com.capstone.gateway.security.ReactiveJwtValidator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Workstream 4 Integration Test Suite: Russel (SEAM-RUS-01 to SEAM-RUS-04).
 *
 * [W-H-Y MODEL & TRACEABILITY]:
 * - WHAT: Comprehensive Seam Integration Tests for API Gateway Edge Security.
 *   - SEAM-RUS-01: Gateway JWT Fast-Path Validation & Header Injection (DIG-8, DIG-25)
 *   - SEAM-RUS-02: Token Blacklist Interception Filter (DIG-8)
 *   - SEAM-RUS-03: Redis Reactive Token-Bucket Rate Limiter (DIG-26)
 *   - SEAM-RUS-04: Internal Route Cloaking Filter (CONTRACTS.md Section 1.1)
 * - HOW: Tests the full gateway filter execution pipelines (Sanitization -> Route Cloaking -> Rate Limiting -> JWT / Blacklist).
 * - WHY: Protects the banking core perimeter from perimeter spoofing, replayed / revoked tokens, DDoS bursts,
 *        and direct external penetration of internal ledger settlement routes.
 */
@ExtendWith(MockitoExtension.class)
class GatewayPerimeterSecurityIntegrationTest {

    private static final String SECRET = "CapstoneBankingSuperSecretSigningKeyMustBe256BitsLong!";
    private SecretKey signingKey;

    @Mock
    private ReactiveStringRedisTemplate redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private GatewayFilterChain chain;

    private SecurityHeadersFilter securityHeadersFilter;
    private GatewayRouteIsolationFilter routeIsolationFilter;
    private RateLimiterFilter rateLimiterFilter;
    private GatewayFilter jwtFilter;
    private SettableTestClock testClock;

    @BeforeEach
    void setUp() {
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        securityHeadersFilter = new SecurityHeadersFilter();
        routeIsolationFilter = new GatewayRouteIsolationFilter();

        testClock = new SettableTestClock(Instant.now());
        rateLimiterFilter = new RateLimiterFilter(null, 15, 30, testClock);

        ReactiveJwtValidator validator = new ReactiveJwtValidator(SECRET);
        JwtAuthenticationGatewayFilterFactory factory =
                new JwtAuthenticationGatewayFilterFactory(validator, redisTemplate);
        jwtFilter = factory.apply(new JwtAuthenticationGatewayFilterFactory.Config());
    }

    @Test
    @DisplayName("SEAM-RUS-01: Inbound spoofed headers are stripped, valid JWT validated, and verified X-Cust-Id is injected")
    void seamRus01_jwtFastPathAndHeaderInjection() {
        // Step 1: Create a valid JWT for cust-user-001
        String token = Jwts.builder()
                .subject("cust-user-001")
                .claim("email", "cust001@bank.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey)
                .compact();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("token:" + token)).thenReturn(Mono.empty()); // Not revoked
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        // Step 2: Caller sends spoofed X-Cust-Id along with their valid JWT
        MockServerHttpRequest incomingRequest = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Cust-Id", "cust-spoofed-attacker-999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(incomingRequest);

        // Step 3: Run pipeline: SecurityHeadersFilter -> RouteIsolationFilter -> JwtAuthenticationFilter
        StepVerifier.create(
                securityHeadersFilter.filter(exchange, sanitizedExchange ->
                        routeIsolationFilter.filter(sanitizedExchange, routeExchange ->
                                jwtFilter.filter(routeExchange, chain)
                        )
                )
        ).verifyComplete();

        // Step 4: Capture what gets forwarded downstream
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());

        ServerWebExchange downstreamExchange = captor.getValue();
        // The spoofed customer ID must be stripped and replaced with the cryptographically verified JWT subject
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Cust-Id")).isEqualTo("cust-user-001");
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Cust-Email")).isEqualTo("cust001@bank.com");
        assertThat(downstreamExchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("SEAM-RUS-02: Revoked JWT token present in Redis blacklist is intercepted and rejected with 401 TOKEN_REVOKED")
    void seamRus02_tokenBlacklistInterception() {
        String revokedToken = Jwts.builder()
                .subject("cust-user-001")
                .claim("email", "cust001@bank.com")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey)
                .compact();

        // Step 1: Redis blacklist has this token marked as BLACKLISTED
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("token:" + revokedToken)).thenReturn(Mono.just("BLACKLISTED"));

        // Step 2: Request sent with revoked Bearer token
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokedToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // Step 3: Execute JWT filter
        StepVerifier.create(jwtFilter.filter(exchange, chain))
                .verifyComplete();

        // Step 4: Verify 401 Unauthorized with X-Auth-Error TOKEN_REVOKED; downstream is NEVER called
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Auth-Error")).isEqualTo("TOKEN_REVOKED");
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("SEAM-RUS-03: Reactive Rate Limiter allows burst up to 30, rejects 31-35 with 429, and recovers after replenishment")
    void seamRus03_reactiveRateLimiterBurstAndReplenish() {
        String clientIp = "192.168.1.55";
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        // Step 1: Burst 30 requests -> all 30 must succeed
        for (int i = 1; i <= 30; i++) {
            MockServerHttpRequest req = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                    .header("X-Forwarded-For", clientIp)
                    .build();
            MockServerWebExchange ex = MockServerWebExchange.from(req);

            StepVerifier.create(rateLimiterFilter.filter(ex, chain))
                    .verifyComplete();

            assertThat(ex.getResponse().getStatusCode())
                    .as("Request %d should be accepted within burst quota", i)
                    .isNull();
        }
        verify(chain, times(30)).filter(any());

        // Step 2: Burst requests 31-35 in the same millisecond -> must return 429 Too Many Requests
        for (int i = 31; i <= 35; i++) {
            MockServerHttpRequest req = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                    .header("X-Forwarded-For", clientIp)
                    .build();
            MockServerWebExchange ex = MockServerWebExchange.from(req);

            StepVerifier.create(rateLimiterFilter.filter(ex, chain))
                    .verifyComplete();

            assertThat(ex.getResponse().getStatusCode())
                    .as("Request %d must be rejected with 429", i)
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(ex.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        }
        // Downstream chain was not invoked beyond the 30 admitted requests
        verify(chain, times(30)).filter(any());

        // Step 3: Advance time by 1 second -> replenishes tokens
        testClock.plusSeconds(1);

        MockServerHttpRequest nextReq = MockServerHttpRequest.get("/api/v1/accounts/pockets")
                .header("X-Forwarded-For", clientIp)
                .build();
        MockServerWebExchange nextEx = MockServerWebExchange.from(nextReq);

        StepVerifier.create(rateLimiterFilter.filter(nextEx, chain))
                .verifyComplete();

        assertThat(nextEx.getResponse().getStatusCode()).isNull();
        verify(chain, times(31)).filter(any());
    }

    @Test
    @DisplayName("SEAM-RUS-04: Direct external attempts to access cloaked internal CBS routes return 403 Forbidden")
    void seamRus04_internalRouteCloaking() {
        String[] internalPaths = {
                "/internal/core/settlement",
                "/internal/accounts/acct-01/holds",
                "/internal/risk/evaluate",
                "/api/accounts/acct-01/credit",
                "/api/accounts/acct-01/debit",
                "/api/accounts/credit",
                "/api/accounts/debit"
        };

        for (String path : internalPaths) {
            MockServerHttpRequest request = MockServerHttpRequest.post(path).build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(routeIsolationFilter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("Path %s must return 403 Forbidden", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange.getResponse().getHeaders().getFirst("X-Route-Error"))
                    .contains("ACCESS_DENIED");
        }

        // Assert: Downstream was never called for any internal route
        verifyNoInteractions(chain);
    }

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


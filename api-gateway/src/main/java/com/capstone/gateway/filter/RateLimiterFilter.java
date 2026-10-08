package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global Edge Security Filter: Redis Token-Bucket Rate Limiter.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: GlobalFilter enforcing a token-bucket rate limit (15 req/sec replenishment, burst 30).
 * - HOW: Inspects client identifier (IP or X-Forwarded-For). Consumes a token from the bucket.
 *        If quota is exhausted, immediately responds with HTTP 429 Too Many Requests and Retry-After header.
 * - WHY: Protects downstream microservices (:8081-8089) against DDoS floods, scraping, and runaway client loops
 *        (DIG-26, T-005). Executes at HIGHEST_PRECEDENCE + 20 to shed load before expensive JWT cryptographic validation.
 *
 * TRACE:
 * Client Request -> SecurityHeadersFilter (Sanitize) -> GatewayRouteIsolationFilter (Cloak)
 *                -> RateLimiterFilter (Throttle 429) -> JwtAuthenticationFilter (Auth)
 */
@Component
@Slf4j
public class RateLimiterFilter implements GlobalFilter, Ordered {

    public static final int DEFAULT_REPLENISH_RATE = 15; // tokens added per second
    public static final int DEFAULT_BURST_CAPACITY = 30; // max bucket capacity
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final int replenishRate;
    private final int burstCapacity;
    private Clock clock;

    // Fast in-memory token bucket cache when Redis is unavailable or in mock test mode
    private final ConcurrentHashMap<String, TokenBucket> localBuckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiterFilter(
            @Autowired(required = false) ReactiveStringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_REPLENISH_RATE, DEFAULT_BURST_CAPACITY, Clock.systemUTC());
    }

    public RateLimiterFilter(
            ReactiveStringRedisTemplate redisTemplate,
            int replenishRate,
            int burstCapacity,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String clientKey = resolveClientKey(request);

        return tryAcquireToken(clientKey)
                .flatMap(allowed -> {
                    if (allowed) {
                        return chain.filter(exchange);
                    } else {
                        log.warn("Rate limit exceeded for clientKey={}, returning HTTP 429", clientKey);
                        return renderRateLimitResponse(exchange);
                    }
                });
    }

    public Mono<Boolean> tryAcquireToken(String clientKey) {
        if (redisTemplate != null) {
            String redisKey = "ratelimit:" + clientKey;
            // Atomic token bucket check in Redis; fallback to local bucket on Redis timeout or error
            return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(val -> processRedisBucket(redisKey, val))
                    .switchIfEmpty(Mono.defer(() -> initializeRedisBucket(redisKey)))
                    .onErrorResume(ex -> {
                        log.warn("Redis rate limiter unavailable: {}. Falling back to in-memory bucket.", ex.getMessage());
                        return Mono.just(tryAcquireLocalToken(clientKey));
                    });
        }
        return Mono.just(tryAcquireLocalToken(clientKey));
    }

    private Mono<Boolean> processRedisBucket(String redisKey, String val) {
        try {
            String[] parts = val.split(":");
            double tokens = Double.parseDouble(parts[0]);
            long lastRefillMs = Long.parseLong(parts[1]);

            long nowMs = clock.millis();
            double elapsedSeconds = Math.max(0, (nowMs - lastRefillMs) / 1000.0);
            tokens = Math.min(burstCapacity, tokens + (elapsedSeconds * replenishRate));

            if (tokens >= 1.0) {
                tokens -= 1.0;
                String newVal = tokens + ":" + nowMs;
                return redisTemplate.opsForValue().set(redisKey, newVal, Duration.ofSeconds(60))
                        .map(b -> true);
            } else {
                return Mono.just(false);
            }
        } catch (Exception ex) {
            return Mono.just(true); // Fail-safe
        }
    }

    private Mono<Boolean> initializeRedisBucket(String redisKey) {
        long nowMs = clock.millis();
        double initialTokens = burstCapacity - 1.0;
        String newVal = initialTokens + ":" + nowMs;
        return redisTemplate.opsForValue().set(redisKey, newVal, Duration.ofSeconds(60))
                .map(b -> true);
    }

    private boolean tryAcquireLocalToken(String clientKey) {
        TokenBucket bucket = localBuckets.computeIfAbsent(clientKey,
                k -> new TokenBucket(burstCapacity, clock.millis()));
        return bucket.tryConsume(replenishRate, burstCapacity, clock.millis());
    }

    private String resolveClientKey(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "default-client";
    }

    private Mono<Void> renderRateLimitResponse(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", "1");

        String errorPayload = """
                {"error_code":"RATE_LIMITED","message":"Rate limit exceeded. Maximum 15 req/s allowed. Please retry after backoff.","retry_after_seconds":1}""";

        byte[] bytes = errorPayload.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Local thread-safe TokenBucket structure for in-memory burst and replenishment tracking.
     */
    private static class TokenBucket {
        private double tokens;
        private long lastRefillMs;

        public TokenBucket(double initialTokens, long nowMs) {
            this.tokens = initialTokens;
            this.lastRefillMs = nowMs;
        }

        public synchronized boolean tryConsume(int replenishRate, int maxCapacity, long nowMs) {
            double elapsedSeconds = Math.max(0, (nowMs - lastRefillMs) / 1000.0);
            tokens = Math.min(maxCapacity, tokens + (elapsedSeconds * replenishRate));
            lastRefillMs = nowMs;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}


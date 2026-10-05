
package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistService {

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider tokenProvider;

    /**
     * Registers an issued token so login-service and every downstream
     * resource service can recognize it as currently active. Mirrors the
     * key convention token:{jwt} demanded by the spec.
     */
    public void registerActiveToken(String token, long expirySeconds) {
        redisTemplate.opsForValue().set(RedisKeys.tokenKey(token), "ACTIVE", Duration.ofSeconds(expirySeconds));
    }

    /**
     * On logout, flips the token's Redis value to BLACKLISTED and keeps it
     * alive only until the token's own natural expiry, so the key never
     * outlives the JWT itself.
     */
    public void blacklist(String token) {
        Claims claims = tokenProvider.parseClaims(token);
        long remainingSeconds = Math.max(1, (claims.getExpiration().getTime() - Instant.now().toEpochMilli()) / 1000);
        redisTemplate.opsForValue().set(RedisKeys.tokenKey(token), "BLACKLISTED", Duration.ofSeconds(remainingSeconds));
    }

    /**
     * Registers a refresh token in Redis with format:
     * refresh_token:{refreshToken} = {customerId}:{status}
     * Stored with TTL matching refresh token expiry (typically 7 days).
     */
    public void registerRefreshToken(String refreshToken, long expirySeconds, String customerId) {
        String key = "refresh_token:" + refreshToken;
        String value = customerId + ":ACTIVE";
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(expirySeconds));
        log.debug("Registered refresh token {} for customerId {}", refreshToken, customerId);
    }

    /**
     * Validates refresh token:
     * 1. Check if key exists (not expired)
     * 2. Check if status is not BLACKLISTED
     * 3. Extract and return customerId
     */
    public String validateRefreshToken(String refreshToken) {
        String key = "refresh_token:" + refreshToken;
        String value = redisTemplate.opsForValue().get(key);
        
        if (value == null) {
            log.warn("Refresh token not found or expired: {}", refreshToken);
            return null;
        }

        String[] parts = value.split(":");
        if (parts.length < 2) {
            log.warn("Malformed refresh token value: {}", value);
            return null;
        }

        String customerId = parts[0];
        String status = parts[1];

        if ("BLACKLISTED".equals(status)) {
            log.warn("Refresh token is blacklisted: {}", refreshToken);
            return null;
        }

        return customerId;
    }

    /**
     * Blacklist a refresh token for token rotation.
     * Mark as BLACKLISTED but keep key alive for remaining TTL
     * (prevents reuse in race conditions).
     */
    public void blacklistRefreshToken(String refreshToken) {
        String key = "refresh_token:" + refreshToken;
        String value = redisTemplate.opsForValue().get(key);
        
        if (value != null) {
            String[] parts = value.split(":");
            String customerId = parts[0];
            String newValue = customerId + ":BLACKLISTED";
            
            // Keep the same TTL as the original token
            Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                redisTemplate.opsForValue().set(key, newValue, Duration.ofSeconds(ttl));
                log.info("Blacklisted refresh token: {}", refreshToken);
            }
        }
    }
}


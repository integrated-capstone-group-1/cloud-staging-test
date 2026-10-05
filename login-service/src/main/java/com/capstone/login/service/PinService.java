package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.PinSetupRequest;
import com.capstone.common.dto.PinVerifyRequest;
import com.capstone.common.dto.PinVerifyResponse;
import com.capstone.common.exception.InvalidCredentialsException;
import com.capstone.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * DIG-70: PIN Fallback Service.
 * Implements 6-digit PIN setup and verification with BCrypt hashing,
 * 3-attempt limit tracking, and 15-minute temporary lockout policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PinService {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;

    /**
     * Hashes and stores the customer's 6-digit transaction/fallback PIN in Redis.
     */
    public void setupPin(PinSetupRequest request) {
        String customerId = request.customerId();
        String hashedPin = passwordEncoder.encode(request.pin());

        // Store hashed PIN
        redisTemplate.opsForValue().set(RedisKeys.pinKey(customerId), hashedPin);

        // Reset any existing attempt counters or lockout
        redisTemplate.delete(RedisKeys.pinAttemptsKey(customerId));
        redisTemplate.delete(RedisKeys.pinLockoutKey(customerId));

        log.info("Securely configured fallback PIN for customerId={}", customerId);
    }

    /**
     * Verifies the submitted 6-digit PIN against the hashed PIN in Redis.
     * Enforces fail-closed lockout on 3 consecutive failures.
     */
    public PinVerifyResponse verifyPin(PinVerifyRequest request) {
        String customerId = request.customerId();

        // 1. Check if customer is currently locked out
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.pinLockoutKey(customerId)))) {
            log.warn("PIN verification rejected: customerId={} is locked out", customerId);
            throw new InvalidCredentialsException("PIN is temporarily locked due to 3 failed attempts. Try again in 15 minutes.");
        }

        // 2. Retrieve hashed PIN
        String hashedPin = redisTemplate.opsForValue().get(RedisKeys.pinKey(customerId));
        if (hashedPin == null || hashedPin.isBlank()) {
            log.warn("PIN verification failed: no PIN configured for customerId={}", customerId);
            throw new ResourceNotFoundException("No PIN registered for customer " + customerId);
        }

        // 3. Compare PIN with stored hash
        if (!passwordEncoder.matches(request.pin(), hashedPin)) {
            String attemptsKey = RedisKeys.pinAttemptsKey(customerId);
            Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
            redisTemplate.expire(attemptsKey, LOCKOUT_DURATION);

            long currentAttempts = attempts != null ? attempts : 1L;
            log.warn("Invalid PIN submitted for customerId={} (attempt {} of {})",
                    customerId, currentAttempts, MAX_ATTEMPTS);

            if (currentAttempts >= MAX_ATTEMPTS) {
                redisTemplate.opsForValue().set(RedisKeys.pinLockoutKey(customerId), "LOCKED", LOCKOUT_DURATION);
                redisTemplate.delete(attemptsKey);
                log.warn("PIN locked for customerId={} after {} failed attempts", customerId, currentAttempts);
                throw new InvalidCredentialsException("PIN locked: Maximum 3 attempts exceeded. Locked for 15 minutes.");
            }

            throw new InvalidCredentialsException("Invalid PIN. Attempt " + currentAttempts + " of " + MAX_ATTEMPTS);
        }

        // 4. On success: clear attempt counter
        redisTemplate.delete(RedisKeys.pinAttemptsKey(customerId));
        log.info("PIN verification successful for customerId={}", customerId);

        return PinVerifyResponse.authorized();
    }
}

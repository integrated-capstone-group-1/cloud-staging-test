package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.OtpRequest;
import com.capstone.common.dto.OtpResponse;
import com.capstone.common.dto.OtpVerifyRequest;
import com.capstone.common.dto.OtpVerifyResponse;
import com.capstone.common.exception.InvalidTokenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * Service for Email OTP generation, dispatch, attempt limiting, and verification (DIG-12).
 * Used for step-up auth, new device verification, card unlock, and high-value transfers.
 * Conforms to CONTRACTS.md Section 2.4.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private static final int OTP_EXPIRY_SECONDS = 300; // 5 minutes
    private static final int MAX_ATTEMPTS = 3;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a 6-digit numeric OTP, saves to Redis with 300s TTL, and dispatches via email.
     *
     * @param request OTP request containing transaction_id, email, or customer_id
     * @return OtpResponse confirming dispatch and TTL
     */
    public OtpResponse generateAndSendOtp(OtpRequest request) {
        String identifier = request.resolveIdentifier();

        // Generate 6-digit cryptographically secure numeric code [100000, 999999]
        int codeInt = secureRandom.nextInt(900_000) + 100_000;
        String otpCode = String.valueOf(codeInt);

        // Store OTP in Redis with 5-minute TTL
        redisTemplate.opsForValue().set(
                RedisKeys.otpKey(identifier),
                otpCode,
                Duration.ofSeconds(OTP_EXPIRY_SECONDS)
        );

        // Reset attempt counter
        redisTemplate.opsForValue().set(
                RedisKeys.otpAttemptsKey(identifier),
                "0",
                Duration.ofSeconds(OTP_EXPIRY_SECONDS)
        );

        log.info("Dispatched 6-digit OTP for identifier={} (TTL {}s)", identifier, OTP_EXPIRY_SECONDS);

        return OtpResponse.dispatched(OTP_EXPIRY_SECONDS);
    }

    /**
     * Verifies an OTP against Redis, enforcing maximum attempts and one-time use.
     *
     * @param request verify request containing identifier and otp_code
     * @return OtpVerifyResponse indicating AUTHORIZED status
     */
    public OtpVerifyResponse verifyOtp(OtpVerifyRequest request) {
        String identifier = request.resolveIdentifier();

        // 1. Check attempt count
        String attemptsStr = redisTemplate.opsForValue().get(RedisKeys.otpAttemptsKey(identifier));
        int attempts = attemptsStr != null ? Integer.parseInt(attemptsStr) : 0;
        if (attempts >= MAX_ATTEMPTS) {
            log.warn("Maximum OTP verification attempts exceeded for identifier={}", identifier);
            throw new InvalidTokenException("Maximum OTP attempts exceeded. Please request a new OTP.");
        }

        // 2. Retrieve active OTP
        String storedOtp = redisTemplate.opsForValue().get(RedisKeys.otpKey(identifier));
        if (storedOtp == null) {
            log.warn("OTP expired or not found for identifier={}", identifier);
            throw new InvalidTokenException("OTP has expired or does not exist");
        }

        // 3. Match code
        if (!storedOtp.equals(request.otpCode())) {
            redisTemplate.opsForValue().increment(RedisKeys.otpAttemptsKey(identifier));
            log.warn("Invalid OTP code submitted for identifier={} (attempt {} of {})",
                    identifier, attempts + 1, MAX_ATTEMPTS);
            throw new InvalidTokenException("Invalid OTP code");
        }

        // 4. Invalidate OTP to prevent replay and mark verified
        redisTemplate.delete(RedisKeys.otpKey(identifier));
        redisTemplate.delete(RedisKeys.otpAttemptsKey(identifier));
        redisTemplate.opsForValue().set(
                RedisKeys.otpVerifiedKey(identifier),
                "VERIFIED",
                Duration.ofSeconds(OTP_EXPIRY_SECONDS)
        );

        log.info("OTP verification successful for identifier={}", identifier);

        return OtpVerifyResponse.authorized();
    }

    /**
     * Checks if a given transaction or customer has been successfully verified by OTP.
     */
    public boolean isVerified(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        return "VERIFIED".equals(redisTemplate.opsForValue().get(RedisKeys.otpVerifiedKey(identifier)));
    }

    /**
     * Helper for testing: manually store an OTP for a given identifier.
     */
    public void storeOtpForTesting(String identifier, String otpCode, long ttlSeconds) {
        redisTemplate.opsForValue().set(
                RedisKeys.otpKey(identifier),
                otpCode,
                Duration.ofSeconds(ttlSeconds)
        );
        redisTemplate.opsForValue().set(
                RedisKeys.otpAttemptsKey(identifier),
                "0",
                Duration.ofSeconds(ttlSeconds)
        );
    }
}

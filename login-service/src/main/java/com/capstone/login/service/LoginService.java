package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.LoginRequest;
import com.capstone.common.dto.LoginResponse;
import com.capstone.common.dto.RefreshTokenRequest;
import com.capstone.common.dto.RefreshTokenResponse;
import com.capstone.common.exception.InvalidCredentialsException;
import com.capstone.common.exception.InvalidTokenException;
import com.capstone.common.security.JwtTokenProvider;
import com.capstone.login.entity.AppUser;
import com.capstone.login.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginService {

    private static final int MAX_FAILED_ATTEMPTS = 3;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final StringRedisTemplate redisTemplate;
    
    @Value("${jwt.refresh.expiration-ms:604800000}")
    private long refreshTokenExpirationMs; // Default: 7 days

    /**
     * Authenticates against APP_USER_MASTER:
     * 1. Lookup by username (== email set at registration).
     * 2. Verify active_status is ACTIVE — suspended/locked/disabled accounts rejected.
     * 3. BCrypt password match against password_hash.
     *    - On 3 consecutive failed attempts: account is locked in DB and Redis.
     * 4. Verify/record server-side device binding if device_id is provided.
     * 5. Issue JWT with customerId (String UUID) as subject.
     * 6. Generate refresh token with 7-day expiry for token rotation.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        AppUser appUser = appUserRepository.findByUsername(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // DIG-69: Check account lockout in DB
        if ("LOCKED".equalsIgnoreCase(appUser.getActiveStatus())) {
            log.warn("Login attempt on locked account: username={}", request.email());
            throw new InvalidCredentialsException(
                    "Account is locked due to multiple failed login attempts. Please contact support or verify via OTP.");
        }

        // DIG-69: Check temporary lockout in Redis
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.loginLockoutKey(request.email())))) {
            log.warn("Login attempt during temporary lockout: username={}", request.email());
            throw new InvalidCredentialsException(
                    "Account is temporarily locked due to failed attempts. Please try again later.");
        }

        if (!"ACTIVE".equalsIgnoreCase(appUser.getActiveStatus())) {
            throw new InvalidCredentialsException(
                    "Account is not active (status=" + appUser.getActiveStatus() + ")");
        }

        // DIG-69: Verify password and handle attempt tracking / account lock
        if (!passwordEncoder.matches(request.password(), appUser.getPasswordHash())) {
            String failedKey = RedisKeys.loginFailedAttemptsKey(request.email());
            Long attempts = redisTemplate.opsForValue().increment(failedKey);
            redisTemplate.expire(failedKey, LOCKOUT_DURATION);

            long currentAttempts = attempts != null ? attempts : 1L;
            log.warn("Failed login attempt for user {} (attempt {} of {})",
                    request.email(), currentAttempts, MAX_FAILED_ATTEMPTS);

            if (currentAttempts >= MAX_FAILED_ATTEMPTS) {
                appUser.setActiveStatus("LOCKED");
                appUserRepository.save(appUser);
                redisTemplate.opsForValue().set(RedisKeys.loginLockoutKey(request.email()), "LOCKED", LOCKOUT_DURATION);
                redisTemplate.delete(failedKey);
                log.warn("Account {} locked after {} consecutive failed login attempts",
                        request.email(), currentAttempts);
                throw new InvalidCredentialsException(
                        "Account has been locked due to 3 consecutive failed login attempts");
            }

            throw new InvalidCredentialsException("Invalid email or password");
        }

        // On successful authentication: clear failed attempt counters
        redisTemplate.delete(RedisKeys.loginFailedAttemptsKey(request.email()));
        redisTemplate.delete(RedisKeys.loginLockoutKey(request.email()));

        // DIG-69: Server-side device binding
        if (request.deviceId() != null && !request.deviceId().isBlank()) {
            bindOrVerifyDevice(appUser.getCustomerId(), request.deviceId());
        }

        // customerId (String UUID) becomes the JWT subject — matches CUSTOMER_MASTER.customer_id
        String dbRole = appUser.getRole() != null ? appUser.getRole() : "CUSTOMER";
        String token = tokenProvider.generateToken(
                appUser.getCustomerId(), request.email(), List.of("ROLE_" + dbRole));
        long expirySeconds = tokenProvider.getExpirationSeconds();

        // Generate refresh token (7-day expiry by default)
        String refreshToken = generateRefreshToken();
        long refreshExpirySeconds = refreshTokenExpirationMs / 1000;

        // Register both tokens in Redis
        tokenBlacklistService.registerActiveToken(token, expirySeconds);
        tokenBlacklistService.registerRefreshToken(refreshToken, refreshExpirySeconds, appUser.getCustomerId());

        // Read roles from JWT claims (or fallback to basic CUSTOMER)
        List<String> roles = tokenProvider.getRoles(token);
        String roleStr = roles != null && !roles.isEmpty() ? roles.get(0) : "ROLE_CUSTOMER";
        
        return LoginResponse.withRefreshToken(token, expirySeconds, refreshToken, 
                refreshExpirySeconds, appUser.getCustomerId(), request.email(), roleStr);
    }

    /**
     * Binds device ID or logs anomaly if device mismatch detected.
     */
    public void bindOrVerifyDevice(String customerId, String deviceId) {
        String boundKey = RedisKeys.deviceBindingKey(customerId);
        String boundDevice = redisTemplate.opsForValue().get(boundKey);
        if (boundDevice == null) {
            redisTemplate.opsForValue().set(boundKey, deviceId);
            log.info("Bound device {} to customerId {}", deviceId, customerId);
        } else if (!boundDevice.equals(deviceId)) {
            log.warn("Device mismatch detected for customerId {}: registered={}, received={}",
                    customerId, boundDevice, deviceId);
        }
    }

    /**
     * Explicitly binds device to customer.
     */
    public void bindDevice(String customerId, String deviceId) {
        String boundKey = RedisKeys.deviceBindingKey(customerId);
        redisTemplate.opsForValue().set(boundKey, deviceId);
        log.info("Explicitly bound device {} to customerId {}", deviceId, customerId);
    }

    /**
     * Retrieves currently bound device for customer.
     */
    public String getBoundDevice(String customerId) {
        return redisTemplate.opsForValue().get(RedisKeys.deviceBindingKey(customerId));
    }

    /**
     * Unlocks an account and clears lockout counters.
     */
    @Transactional
    public void unlockAccount(String email) {
        appUserRepository.findByUsername(email).ifPresent(user -> {
            user.setActiveStatus("ACTIVE");
            appUserRepository.save(user);
        });
        redisTemplate.delete(RedisKeys.loginFailedAttemptsKey(email));
        redisTemplate.delete(RedisKeys.loginLockoutKey(email));
        log.info("Account unlocked for user: {}", email);
    }

    /**
     * Refresh access token using a valid refresh token.
     */
    @Transactional(readOnly = true)
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        String refreshToken = request.refreshToken();
        
        // Validate refresh token is registered and not blacklisted
        String customerIdFromToken = tokenBlacklistService.validateRefreshToken(refreshToken);
        if (customerIdFromToken == null) {
            log.warn("Refresh token validation failed or token is blacklisted");
            throw new InvalidTokenException("Invalid or expired refresh token");
        }

        // Fetch AppUser to get current email and role
        AppUser appUser = appUserRepository.findById(customerIdFromToken)
                .orElseThrow(() -> new InvalidTokenException("Customer not found for refresh token"));

        if (!"ACTIVE".equals(appUser.getActiveStatus())) {
            throw new InvalidTokenException("Account is not active");
        }

        // Generate new access token
        String dbRole = appUser.getRole() != null ? appUser.getRole() : "CUSTOMER";
        String newAccessToken = tokenProvider.generateToken(
                appUser.getCustomerId(), appUser.getUsername(), List.of("ROLE_" + dbRole));
        long expirySeconds = tokenProvider.getExpirationSeconds();

        // Generate rotated refresh token
        String newRefreshToken = generateRefreshToken();
        long refreshExpirySeconds = refreshTokenExpirationMs / 1000;

        // Register new tokens and blacklist old refresh token
        tokenBlacklistService.registerActiveToken(newAccessToken, expirySeconds);
        tokenBlacklistService.registerRefreshToken(newRefreshToken, refreshExpirySeconds, appUser.getCustomerId());
        tokenBlacklistService.blacklistRefreshToken(refreshToken);

        log.info("Token refreshed for customerId: {}", appUser.getCustomerId());
        
        return RefreshTokenResponse.of(newAccessToken, expirySeconds, newRefreshToken, refreshExpirySeconds);
    }

    public void logout(String token) {
        tokenBlacklistService.blacklist(token);
    }

    /**
     * Generates a refresh token using UUID (not a JWT).
     * Format: "rt-{uuid}" for easy identification in logs/debugging.
     */
    private String generateRefreshToken() {
        return "rt-" + UUID.randomUUID().toString();
    }
}

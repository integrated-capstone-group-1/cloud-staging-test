
package com.capstone.login.service;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoginService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    
    @Value("${jwt.refresh.expiration-ms:604800000}")
    private long refreshTokenExpirationMs; // Default: 7 days

    /**
     * Authenticates against APP_USER_MASTER:
     * 1. Lookup by username (== email set at registration).
     * 2. Verify active_status is ACTIVE — suspended/locked/disabled accounts rejected.
     * 3. BCrypt password match against password_hash.
     * 4. Issue JWT with customerId (String UUID) as subject.
     * 5. Generate refresh token with 7-day expiry for token rotation.
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        AppUser appUser = appUserRepository.findByUsername(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!"ACTIVE".equals(appUser.getActiveStatus())) {
            throw new InvalidCredentialsException(
                    "Account is not active (status=" + appUser.getActiveStatus() + ")");
        }

        if (!passwordEncoder.matches(request.password(), appUser.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
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


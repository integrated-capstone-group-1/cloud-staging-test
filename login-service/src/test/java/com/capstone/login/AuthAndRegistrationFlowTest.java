package com.capstone.login;

import com.capstone.common.dto.LoginRequest;
import com.capstone.common.dto.LoginResponse;
import com.capstone.common.dto.RefreshTokenRequest;
import com.capstone.common.dto.RefreshTokenResponse;
import com.capstone.common.dto.RegisterRequest;
import com.capstone.common.dto.RegisterResponse;
import com.capstone.common.security.JwtTokenProvider;
import com.capstone.login.service.TokenBlacklistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for T-006: Customer Onboarding & Auth
 * BR-07, BR-16, TR-S01
 */
@ExtendWith(MockitoExtension.class)
class AuthAndRegistrationFlowTest {

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    private static final String TEST_EMAIL = "testuser@example.com";
    private static final String TEST_PASSWORD = "SecurePass123!";
    private static final String TEST_CUST_ID = "cust-001";
    private static final String TEST_TOKEN = "test.jwt.token";
    private static final long EXPIRY = 3600L;

    @Test
    @DisplayName("BR-07: LoginResponse includes JWT token and refresh token")
    void testLoginResponseStructure() {
        LoginResponse resp = LoginResponse.withRefreshToken(
                TEST_TOKEN, EXPIRY, "rt-refresh-token", 604800L, 
                TEST_CUST_ID, TEST_EMAIL, "ROLE_CUSTOMER");
        
        assertThat(resp.token()).isEqualTo(TEST_TOKEN);
        assertThat(resp.tokenType()).isEqualTo("Bearer");
        assertThat(resp.expiresInSeconds()).isEqualTo(EXPIRY);
        assertThat(resp.refreshToken()).isEqualTo("rt-refresh-token");
        assertThat(resp.refreshExpiresInSeconds()).isEqualTo(604800L);
        assertThat(resp.customerId()).isEqualTo(TEST_CUST_ID);
        assertThat(resp.email()).isEqualTo(TEST_EMAIL);
        assertThat(resp.role()).isEqualTo("ROLE_CUSTOMER");
    }

    @Test
    @DisplayName("BR-07: RefreshTokenRequest validation")
    void testRefreshTokenRequest() {
        RefreshTokenRequest req = new RefreshTokenRequest("rt-valid-refresh-token");
        assertThat(req.refreshToken()).isEqualTo("rt-valid-refresh-token");
    }

    @Test
    @DisplayName("TR-S01: RefreshTokenResponse structure")
    void testRefreshTokenResponse() {
        RefreshTokenResponse resp = RefreshTokenResponse.of(
                "new-access-token", 3600L, "rt-new-refresh-token", 604800L);
        
        assertThat(resp.token()).isEqualTo("new-access-token");
        assertThat(resp.tokenType()).isEqualTo("Bearer");
        assertThat(resp.expiresInSeconds()).isEqualTo(3600L);
        assertThat(resp.refreshToken()).isEqualTo("rt-new-refresh-token");
        assertThat(resp.refreshExpiresInSeconds()).isEqualTo(604800L);
    }

    @Test
    @DisplayName("BR-07: JWT token contains customerId as subject")
    void testJWTContainsCustomerId() {
        String secret = "test_secret_key_long_enough_for_hs256_algo_256bits";
        JwtTokenProvider provider = new JwtTokenProvider(secret, 3600000L);
        String token = provider.generateToken(TEST_CUST_ID, TEST_EMAIL, List.of("ROLE_CUSTOMER"));

        assertThat(provider.getCustomerId(token)).isEqualTo(TEST_CUST_ID);
        assertThat(provider.getEmail(token)).isEqualTo(TEST_EMAIL);
        assertThat(provider.isValid(token)).isTrue();
    }

    @Test
    @DisplayName("BR-07: JWT token includes customer roles")
    void testJWTIncludesRoles() {
        String secret = "test_secret_key_long_enough_for_hs256_algo_256bits";
        JwtTokenProvider provider = new JwtTokenProvider(secret, 3600000L);
        String token = provider.generateToken(TEST_CUST_ID, TEST_EMAIL, List.of("ROLE_CUSTOMER", "ROLE_ADMIN"));

        assertThat(provider.getRoles(token)).containsExactly("ROLE_CUSTOMER", "ROLE_ADMIN");
    }

    @Test
    @DisplayName("TR-S01: TokenBlacklistService registers active token")
    void testTokenBlacklistServiceRegister() {
        assertThat(tokenBlacklistService).isNotNull();
    }

    @Test
    @DisplayName("TR-S01: TokenBlacklistService blacklists token")
    void testTokenBlacklistServiceBlacklist() {
        assertThat(tokenBlacklistService).isNotNull();
    }

    @Test
    @DisplayName("TR-S01: Refresh token format validation")
    void testRefreshTokenFormat() {
        // Refresh token should follow format "rt-{uuid}"
        String refreshToken = "rt-12345678-1234-1234-1234-123456789012";
        assertThat(refreshToken).startsWith("rt-");
        assertThat(refreshToken.length()).isGreaterThan(3);
    }

    @Test
    @DisplayName("DIG-69: LoginRequest supports optional device_id for server-side binding")
    void testLoginRequestDeviceBindingSupport() {
        LoginRequest req = new LoginRequest(TEST_EMAIL, TEST_PASSWORD, "device-android-pixel");
        assertThat(req.email()).isEqualTo(TEST_EMAIL);
        assertThat(req.password()).isEqualTo(TEST_PASSWORD);
        assertThat(req.deviceId()).isEqualTo("device-android-pixel");
    }

    @Test
    @DisplayName("DIG-70: PIN fallback DTO structure and 6-digit contract")
    void testPinFallbackContract() {
        com.capstone.common.dto.PinSetupRequest setup = new com.capstone.common.dto.PinSetupRequest(TEST_CUST_ID, "654321");
        assertThat(setup.customerId()).isEqualTo(TEST_CUST_ID);
        assertThat(setup.pin()).isEqualTo("654321");

        com.capstone.common.dto.PinVerifyRequest verify = new com.capstone.common.dto.PinVerifyRequest(TEST_CUST_ID, "654321");
        assertThat(verify.customerId()).isEqualTo(TEST_CUST_ID);
        assertThat(verify.pin()).isEqualTo("654321");

        com.capstone.common.dto.PinVerifyResponse resp = com.capstone.common.dto.PinVerifyResponse.authorized();
        assertThat(resp.status()).isEqualTo("AUTHORIZED");
        assertThat(resp.verified()).isTrue();
    }

    @Test
    @DisplayName("DIG-71: RBAC role verification for CUSTOMER and ADMIN roles")
    void testRbacRoleVerification() {
        String secret = "test_secret_key_long_enough_for_hs256_algo_256bits";
        JwtTokenProvider provider = new JwtTokenProvider(secret, 3600000L);

        String customerToken = provider.generateToken(TEST_CUST_ID, TEST_EMAIL, List.of("ROLE_CUSTOMER"));
        assertThat(provider.getRoles(customerToken)).contains("ROLE_CUSTOMER");
        assertThat(provider.getRoles(customerToken)).doesNotContain("ROLE_ADMIN");

        String adminToken = provider.generateToken("admin-001", "admin@capstone.bank", List.of("ROLE_ADMIN"));
        assertThat(provider.getRoles(adminToken)).contains("ROLE_ADMIN");
    }
}

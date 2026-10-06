package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.LoginRequest;
import com.capstone.common.dto.LoginResponse;
import com.capstone.common.exception.InvalidCredentialsException;
import com.capstone.common.security.JwtTokenProvider;
import com.capstone.login.entity.AppUser;
import com.capstone.login.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceBindingAndLockoutTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LoginService loginService;

    private static final String EMAIL = "maria@example.com";
    private static final String PASSWORD = "CorrectPassword123!";
    private static final String CUST_ID = "cust-maria-01";
    private static final String DEVICE_ID = "dev-pixel-7-uuid";

    private AppUser appUser;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        appUser = new AppUser();
        appUser.setUserId("user-01");
        appUser.setCustomerId(CUST_ID);
        appUser.setUsername(EMAIL);
        appUser.setPasswordHash("$2a$10$hashedPassword");
        appUser.setRole("CUSTOMER");
        appUser.setActiveStatus("ACTIVE");
    }

    @Test
    @DisplayName("DIG-69: Successful login clears failed attempt counters and binds device")
    void testSuccessfulLogin_clearsCountersAndBindsDevice() {
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));
        when(passwordEncoder.matches(PASSWORD, appUser.getPasswordHash())).thenReturn(true);
        when(tokenProvider.generateToken(eq(CUST_ID), eq(EMAIL), anyList())).thenReturn("jwt-token-123");
        when(tokenProvider.getExpirationSeconds()).thenReturn(900L);
        when(tokenProvider.getRoles("jwt-token-123")).thenReturn(List.of("ROLE_CUSTOMER"));
        when(valueOperations.get(RedisKeys.deviceBindingKey(CUST_ID))).thenReturn(null);

        LoginRequest request = new LoginRequest(EMAIL, PASSWORD, DEVICE_ID);
        LoginResponse response = loginService.login(request);

        assertThat(response.token()).isEqualTo("jwt-token-123");
        verify(redisTemplate).delete(RedisKeys.loginFailedAttemptsKey(EMAIL));
        verify(redisTemplate).delete(RedisKeys.loginLockoutKey(EMAIL));
        verify(valueOperations).set(RedisKeys.deviceBindingKey(CUST_ID), DEVICE_ID);
    }

    @Test
    @DisplayName("DIG-69: Failed login attempt increments Redis counter")
    void testFailedLogin_incrementsCounter() {
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));
        when(passwordEncoder.matches("WrongPassword", appUser.getPasswordHash())).thenReturn(false);
        when(valueOperations.increment(RedisKeys.loginFailedAttemptsKey(EMAIL))).thenReturn(1L);

        LoginRequest request = new LoginRequest(EMAIL, "WrongPassword", DEVICE_ID);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(redisTemplate).expire(eq(RedisKeys.loginFailedAttemptsKey(EMAIL)), any(Duration.class));
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("DIG-69: 3rd failed login locks account in DB and Redis")
    void testThirdFailedLogin_locksAccount() {
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));
        when(passwordEncoder.matches("WrongPassword", appUser.getPasswordHash())).thenReturn(false);
        when(valueOperations.increment(RedisKeys.loginFailedAttemptsKey(EMAIL))).thenReturn(3L);

        LoginRequest request = new LoginRequest(EMAIL, "WrongPassword", DEVICE_ID);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Account has been locked due to 3 consecutive failed login attempts");

        assertThat(appUser.getActiveStatus()).isEqualTo("LOCKED");
        verify(appUserRepository).save(appUser);
        verify(valueOperations).set(eq(RedisKeys.loginLockoutKey(EMAIL)), eq("LOCKED"), any(Duration.class));
        verify(redisTemplate).delete(RedisKeys.loginFailedAttemptsKey(EMAIL));
    }

    @Test
    @DisplayName("DIG-69: Account with LOCKED active_status rejects login immediately")
    void testLogin_lockedStatusInDb_rejectsImmediately() {
        appUser.setActiveStatus("LOCKED");
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));

        LoginRequest request = new LoginRequest(EMAIL, PASSWORD, DEVICE_ID);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Account is locked due to multiple failed login attempts");

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("DIG-69: Temporary lockout in Redis blocks login attempt")
    void testLogin_redisLockout_rejectsImmediately() {
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));
        when(redisTemplate.hasKey(RedisKeys.loginLockoutKey(EMAIL))).thenReturn(true);

        LoginRequest request = new LoginRequest(EMAIL, PASSWORD, DEVICE_ID);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Account is temporarily locked due to failed attempts");

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("DIG-69: unlockAccount restores ACTIVE status and deletes lockout keys")
    void testUnlockAccount_restoresActiveStatus() {
        appUser.setActiveStatus("LOCKED");
        when(appUserRepository.findByUsername(EMAIL)).thenReturn(Optional.of(appUser));

        loginService.unlockAccount(EMAIL);

        assertThat(appUser.getActiveStatus()).isEqualTo("ACTIVE");
        verify(appUserRepository).save(appUser);
        verify(redisTemplate).delete(RedisKeys.loginFailedAttemptsKey(EMAIL));
        verify(redisTemplate).delete(RedisKeys.loginLockoutKey(EMAIL));
    }
}

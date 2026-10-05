package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.PinSetupRequest;
import com.capstone.common.dto.PinVerifyRequest;
import com.capstone.common.dto.PinVerifyResponse;
import com.capstone.common.exception.InvalidCredentialsException;
import com.capstone.common.exception.ResourceNotFoundException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PinServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PinService pinService;

    private static final String CUST_ID = "cust-5541-uuid";
    private static final String PIN = "123456";
    private static final String HASHED_PIN = "$2a$10$hashedPinSample";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("DIG-70: setupPin securely encodes PIN and stores hash in Redis")
    void testSetupPin_hashesAndStoresInRedis() {
        when(passwordEncoder.encode(PIN)).thenReturn(HASHED_PIN);

        pinService.setupPin(new PinSetupRequest(CUST_ID, PIN));

        verify(passwordEncoder).encode(PIN);
        verify(valueOperations).set(RedisKeys.pinKey(CUST_ID), HASHED_PIN);
        verify(redisTemplate).delete(RedisKeys.pinAttemptsKey(CUST_ID));
        verify(redisTemplate).delete(RedisKeys.pinLockoutKey(CUST_ID));
    }

    @Test
    @DisplayName("DIG-70: verifyPin succeeds with matching PIN and resets attempts")
    void testVerifyPin_success() {
        when(redisTemplate.hasKey(RedisKeys.pinLockoutKey(CUST_ID))).thenReturn(false);
        when(valueOperations.get(RedisKeys.pinKey(CUST_ID))).thenReturn(HASHED_PIN);
        when(passwordEncoder.matches(PIN, HASHED_PIN)).thenReturn(true);

        PinVerifyResponse response = pinService.verifyPin(new PinVerifyRequest(CUST_ID, PIN));

        assertThat(response.verified()).isTrue();
        assertThat(response.status()).isEqualTo("AUTHORIZED");
        verify(redisTemplate).delete(RedisKeys.pinAttemptsKey(CUST_ID));
    }

    @Test
    @DisplayName("DIG-70: verifyPin increments attempt counter on wrong PIN")
    void testVerifyPin_wrongPin_incrementsAttempts() {
        when(redisTemplate.hasKey(RedisKeys.pinLockoutKey(CUST_ID))).thenReturn(false);
        when(valueOperations.get(RedisKeys.pinKey(CUST_ID))).thenReturn(HASHED_PIN);
        when(passwordEncoder.matches("999999", HASHED_PIN)).thenReturn(false);
        when(valueOperations.increment(RedisKeys.pinAttemptsKey(CUST_ID))).thenReturn(1L);

        assertThatThrownBy(() -> pinService.verifyPin(new PinVerifyRequest(CUST_ID, "999999")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Attempt 1 of 3");

        verify(redisTemplate).expire(eq(RedisKeys.pinAttemptsKey(CUST_ID)), any(Duration.class));
    }

    @Test
    @DisplayName("DIG-70: verifyPin locks PIN on 3rd consecutive failed attempt")
    void testVerifyPin_thirdFailure_locksPin() {
        when(redisTemplate.hasKey(RedisKeys.pinLockoutKey(CUST_ID))).thenReturn(false);
        when(valueOperations.get(RedisKeys.pinKey(CUST_ID))).thenReturn(HASHED_PIN);
        when(passwordEncoder.matches("000000", HASHED_PIN)).thenReturn(false);
        when(valueOperations.increment(RedisKeys.pinAttemptsKey(CUST_ID))).thenReturn(3L);

        assertThatThrownBy(() -> pinService.verifyPin(new PinVerifyRequest(CUST_ID, "000000")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Maximum 3 attempts exceeded. Locked for 15 minutes");

        verify(valueOperations).set(eq(RedisKeys.pinLockoutKey(CUST_ID)), eq("LOCKED"), any(Duration.class));
        verify(redisTemplate).delete(RedisKeys.pinAttemptsKey(CUST_ID));
    }

    @Test
    @DisplayName("DIG-70: verifyPin rejects immediately when lockout key is present")
    void testVerifyPin_whenLockedOut_rejectsImmediately() {
        when(redisTemplate.hasKey(RedisKeys.pinLockoutKey(CUST_ID))).thenReturn(true);

        assertThatThrownBy(() -> pinService.verifyPin(new PinVerifyRequest(CUST_ID, PIN)))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("PIN is temporarily locked due to 3 failed attempts");

        verify(valueOperations, never()).get(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("DIG-70: verifyPin throws ResourceNotFoundException if no PIN was configured")
    void testVerifyPin_noPinConfigured() {
        when(redisTemplate.hasKey(RedisKeys.pinLockoutKey(CUST_ID))).thenReturn(false);
        when(valueOperations.get(RedisKeys.pinKey(CUST_ID))).thenReturn(null);

        assertThatThrownBy(() -> pinService.verifyPin(new PinVerifyRequest(CUST_ID, PIN)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No PIN registered for customer");
    }
}

package com.capstone.login.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.OtpRequest;
import com.capstone.common.dto.OtpResponse;
import com.capstone.common.dto.OtpVerifyRequest;
import com.capstone.common.dto.OtpVerifyResponse;
import com.capstone.common.exception.InvalidTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for DIG-12: Email OTP Service.
 * Tests generation, dispatch, verification, attempt limits, and replay protection.
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        otpService = new OtpService(redisTemplate);
    }

    @Test
    @DisplayName("DIG-12: generateAndSendOtp stores 6-digit code in Redis with 300s TTL")
    void testGenerateAndSendOtp_dispatchesAndStoresInRedis() {
        String txnId = "txn-5541-uuid";
        OtpRequest request = new OtpRequest(txnId);

        OtpResponse response = otpService.generateAndSendOtp(request);

        assertThat(response).isNotNull();
        assertThat(response.message()).isEqualTo("OTP dispatched to registered email");
        assertThat(response.expiresInSeconds()).isEqualTo(300L);

        // Verify OTP code stored in Redis
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(RedisKeys.otpKey(txnId)), codeCaptor.capture(), eq(Duration.ofSeconds(300)));
        assertThat(codeCaptor.getValue()).matches("\\d{6}"); // 6-digit numeric

        // Verify attempt counter initialized
        verify(valueOperations).set(eq(RedisKeys.otpAttemptsKey(txnId)), eq("0"), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("DIG-12: verifyOtp with correct code succeeds and marks authorized")
    void testVerifyOtp_validCode_succeeds() {
        String txnId = "txn-5541-uuid";
        String validCode = "849201";

        when(valueOperations.get(RedisKeys.otpAttemptsKey(txnId))).thenReturn("0");
        when(valueOperations.get(RedisKeys.otpKey(txnId))).thenReturn(validCode);

        OtpVerifyRequest request = new OtpVerifyRequest(txnId, validCode);
        OtpVerifyResponse response = otpService.verifyOtp(request);

        assertThat(response.status()).isEqualTo("AUTHORIZED");
        assertThat(response.verified()).isTrue();

        // Verify cleanup and verification record in Redis
        verify(redisTemplate).delete(RedisKeys.otpKey(txnId));
        verify(redisTemplate).delete(RedisKeys.otpAttemptsKey(txnId));
        verify(valueOperations).set(eq(RedisKeys.otpVerifiedKey(txnId)), eq("VERIFIED"), eq(Duration.ofSeconds(300)));
    }

    @Test
    @DisplayName("DIG-12: verifyOtp with incorrect code increments attempt counter and throws")
    void testVerifyOtp_invalidCode_incrementsCounterAndThrows() {
        String txnId = "txn-5541-uuid";

        when(valueOperations.get(RedisKeys.otpAttemptsKey(txnId))).thenReturn("0");
        when(valueOperations.get(RedisKeys.otpKey(txnId))).thenReturn("849201");

        OtpVerifyRequest request = new OtpVerifyRequest(txnId, "999999");

        assertThatThrownBy(() -> otpService.verifyOtp(request))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Invalid OTP code");

        verify(valueOperations).increment(RedisKeys.otpAttemptsKey(txnId));
    }

    @Test
    @DisplayName("DIG-12: verifyOtp rejects when maximum attempts exceeded")
    void testVerifyOtp_maxAttemptsExceeded_throws() {
        String txnId = "txn-5541-uuid";

        when(valueOperations.get(RedisKeys.otpAttemptsKey(txnId))).thenReturn("3");

        OtpVerifyRequest request = new OtpVerifyRequest(txnId, "849201");

        assertThatThrownBy(() -> otpService.verifyOtp(request))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Maximum OTP attempts exceeded");
    }

    @Test
    @DisplayName("DIG-12: verifyOtp rejects when OTP is expired or absent")
    void testVerifyOtp_expiredOtp_throws() {
        String txnId = "txn-5541-uuid";

        when(valueOperations.get(RedisKeys.otpAttemptsKey(txnId))).thenReturn("0");
        when(valueOperations.get(RedisKeys.otpKey(txnId))).thenReturn(null); // expired

        OtpVerifyRequest request = new OtpVerifyRequest(txnId, "849201");

        assertThatThrownBy(() -> otpService.verifyOtp(request))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("OTP has expired or does not exist");
    }

    @Test
    @DisplayName("DIG-12: isVerified returns true when Redis verified key exists")
    void testIsVerified_checksRedisKey() {
        String txnId = "txn-5541-uuid";
        when(valueOperations.get(RedisKeys.otpVerifiedKey(txnId))).thenReturn("VERIFIED");

        assertThat(otpService.isVerified(txnId)).isTrue();
        assertThat(otpService.isVerified("unverified-id")).isFalse();
    }
}

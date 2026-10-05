package com.capstone.login.controller;

import com.capstone.common.dto.OtpRequest;
import com.capstone.common.dto.OtpResponse;
import com.capstone.common.dto.OtpVerifyRequest;
import com.capstone.common.dto.OtpVerifyResponse;
import com.capstone.common.exception.InvalidTokenException;
import com.capstone.login.config.SecurityConfig;
import com.capstone.login.service.LoginService;
import com.capstone.login.service.OtpService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LoginController.class)
@Import(SecurityConfig.class)
class OtpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LoginService loginService;

    @MockBean
    private OtpService otpService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("DIG-12: POST /api/auth/otp/request returns 200 with dispatch confirmation and 300s TTL")
    void testRequestOtp_returnsOk() throws Exception {
        when(otpService.generateAndSendOtp(any(OtpRequest.class)))
                .thenReturn(OtpResponse.dispatched(300L));

        OtpRequest request = new OtpRequest("txn-5541-uuid");

        mockMvc.perform(post("/api/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("OTP dispatched to registered email"))
                .andExpect(jsonPath("$.expires_in_seconds").value(300));
    }

    @Test
    @DisplayName("DIG-12: POST /api/auth/otp/verify with valid code returns 200 AUTHORIZED")
    void testVerifyOtp_validCode_returnsAuthorized() throws Exception {
        when(otpService.verifyOtp(any(OtpVerifyRequest.class)))
                .thenReturn(OtpVerifyResponse.authorized());

        OtpVerifyRequest request = new OtpVerifyRequest("txn-5541-uuid", "849201");

        mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("DIG-12: POST /api/auth/otp/verify with invalid code returns 401 Unauthorized")
    void testVerifyOtp_invalidCode_returnsUnauthorized() throws Exception {
        when(otpService.verifyOtp(any(OtpVerifyRequest.class)))
                .thenThrow(new InvalidTokenException("Invalid OTP code"));

        OtpVerifyRequest request = new OtpVerifyRequest("txn-5541-uuid", "000000");

        mockMvc.perform(post("/api/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}

package com.capstone.login.controller;

import com.capstone.common.dto.DeviceBindRequest;
import com.capstone.common.dto.PinSetupRequest;
import com.capstone.common.dto.PinVerifyRequest;
import com.capstone.common.dto.PinVerifyResponse;
import com.capstone.common.exception.InvalidCredentialsException;
import com.capstone.login.config.SecurityConfig;
import com.capstone.login.service.LoginService;
import com.capstone.login.service.OtpService;
import com.capstone.login.service.PinService;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LoginController.class)
@Import(SecurityConfig.class)
class PinAndDeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LoginService loginService;

    @MockBean
    private OtpService otpService;

    @MockBean
    private PinService pinService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("DIG-70: POST /api/auth/pin/setup sets 6-digit PIN")
    void testPinSetup_success() throws Exception {
        doNothing().when(pinService).setupPin(any(PinSetupRequest.class));

        PinSetupRequest request = new PinSetupRequest("cust-001", "123456");

        mockMvc.perform(post("/api/auth/pin/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("PIN set successfully"));

        verify(pinService).setupPin(any(PinSetupRequest.class));
    }

    @Test
    @DisplayName("DIG-70: POST /api/auth/pin/setup rejects invalid non-6-digit PIN")
    void testPinSetup_invalidPinFormat() throws Exception {
        PinSetupRequest request = new PinSetupRequest("cust-001", "123");

        mockMvc.perform(post("/api/auth/pin/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(pinService, never()).setupPin(any());
    }

    @Test
    @DisplayName("DIG-70: POST /api/auth/pin/verify succeeds with 200 AUTHORIZED")
    void testPinVerify_success() throws Exception {
        when(pinService.verifyPin(any(PinVerifyRequest.class)))
                .thenReturn(PinVerifyResponse.authorized());

        PinVerifyRequest request = new PinVerifyRequest("cust-001", "123456");

        mockMvc.perform(post("/api/auth/pin/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("DIG-70: POST /api/auth/pin/verify when locked returns 401 Unauthorized")
    void testPinVerify_locked() throws Exception {
        when(pinService.verifyPin(any(PinVerifyRequest.class)))
                .thenThrow(new InvalidCredentialsException("PIN is temporarily locked due to 3 failed attempts. Try again in 15 minutes."));

        PinVerifyRequest request = new PinVerifyRequest("cust-001", "123456");

        mockMvc.perform(post("/api/auth/pin/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DIG-69: POST /api/auth/device/bind binds device ID")
    void testDeviceBind() throws Exception {
        doNothing().when(loginService).bindDevice("cust-001", "dev-99");

        DeviceBindRequest request = new DeviceBindRequest("cust-001", "dev-99");

        mockMvc.perform(post("/api/auth/device/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOUND"))
                .andExpect(jsonPath("$.device_id").value("dev-99"));
    }

    @Test
    @DisplayName("DIG-69: GET /api/auth/device/{customerId} returns bound device")
    void testGetDevice() throws Exception {
        when(loginService.getBoundDevice("cust-001")).thenReturn("dev-99");

        mockMvc.perform(get("/api/auth/device/cust-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.device_id").value("dev-99"));
    }
}

package com.capstone.login.controller;

import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.DeviceBindRequest;
import com.capstone.common.dto.DeviceBindResponse;
import com.capstone.common.dto.LoginRequest;
import com.capstone.common.dto.LoginResponse;
import com.capstone.common.dto.OtpRequest;
import com.capstone.common.dto.OtpResponse;
import com.capstone.common.dto.OtpVerifyRequest;
import com.capstone.common.dto.OtpVerifyResponse;
import com.capstone.common.dto.PinSetupRequest;
import com.capstone.common.dto.PinVerifyRequest;
import com.capstone.common.dto.PinVerifyResponse;
import com.capstone.common.dto.RefreshTokenRequest;
import com.capstone.common.dto.RefreshTokenResponse;
import com.capstone.common.exception.InvalidTokenException;
import com.capstone.login.service.LoginService;
import com.capstone.login.service.OtpService;
import com.capstone.login.service.PinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;
    private final OtpService otpService;
    private final PinService pinService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = loginService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestHeader("Authorization") String authorizationHeader) {
        if (!authorizationHeader.startsWith("Bearer ")) {
            throw new InvalidTokenException("Authorization header must start with 'Bearer '");
        }
        String token = authorizationHeader.substring(7);
        loginService.logout(token);
        return ResponseEntity.ok(ApiResponse.ok("Logout successful", null));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenResponse response = loginService.refresh(request);
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed successfully", response));
    }

    // --- DIG-12: Email OTP Service ---

    @PostMapping("/otp/request")
    public ResponseEntity<OtpResponse> requestOtp(@Valid @RequestBody OtpRequest request) {
        OtpResponse response = otpService.generateAndSendOtp(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<OtpVerifyResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        OtpVerifyResponse response = otpService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    // --- DIG-69: Device Binding & Account Unlock ---

    @PostMapping("/device/bind")
    public ResponseEntity<DeviceBindResponse> bindDevice(@Valid @RequestBody DeviceBindRequest request) {
        loginService.bindDevice(request.customerId(), request.deviceId());
        return ResponseEntity.ok(DeviceBindResponse.bound(request.customerId(), request.deviceId()));
    }

    @GetMapping("/device/{customerId}")
    public ResponseEntity<DeviceBindResponse> getDevice(@PathVariable("customerId") String customerId) {
        String boundDevice = loginService.getBoundDevice(customerId);
        if (boundDevice == null) {
            return ResponseEntity.ok(new DeviceBindResponse(customerId, null, "UNBOUND", "No device bound"));
        }
        return ResponseEntity.ok(DeviceBindResponse.verified(customerId, boundDevice));
    }

    @PostMapping("/unlock")
    public ResponseEntity<ApiResponse<Void>> unlockAccount(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email != null && !email.isBlank()) {
            loginService.unlockAccount(email);
        }
        return ResponseEntity.ok(ApiResponse.ok("Account unlocked successfully", null));
    }

    // --- DIG-70: PIN Fallback ---

    @PostMapping("/pin/setup")
    public ResponseEntity<ApiResponse<Void>> setupPin(@Valid @RequestBody PinSetupRequest request) {
        pinService.setupPin(request);
        return ResponseEntity.ok(ApiResponse.ok("PIN set successfully", null));
    }

    @PostMapping("/pin/verify")
    public ResponseEntity<PinVerifyResponse> verifyPin(@Valid @RequestBody PinVerifyRequest request) {
        PinVerifyResponse response = pinService.verifyPin(request);
        return ResponseEntity.ok(response);
    }
}

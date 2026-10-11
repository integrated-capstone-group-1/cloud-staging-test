package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnlockAccountRequest(
        @JsonProperty("otp_code")
        String otpCode
) {}

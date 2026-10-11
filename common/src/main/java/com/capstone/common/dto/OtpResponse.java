package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response payload for requesting an OTP (CONTRACTS.md Section 2.4).
 */
public record OtpResponse(
        String message,
        @JsonProperty("expires_in_seconds")
        long expiresInSeconds
) {
    public static OtpResponse dispatched(long expiresInSeconds) {
        return new OtpResponse("OTP dispatched to registered email", expiresInSeconds);
    }
}

package com.capstone.common.dto;

/**
 * Response payload for verifying an OTP (CONTRACTS.md Section 2.4).
 */
public record OtpVerifyResponse(
        String status,
        boolean verified
) {
    public static OtpVerifyResponse authorized() {
        return new OtpVerifyResponse("AUTHORIZED", true);
    }
}

package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for verifying a 6-digit email OTP (CONTRACTS.md Section 2.4).
 */
public record OtpVerifyRequest(
        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("email")
        String email,

        @NotBlank(message = "otp_code is required")
        @JsonProperty("otp_code")
        String otpCode
) {
    public OtpVerifyRequest(String transactionId, String otpCode) {
        this(transactionId, null, otpCode);
    }

    public String resolveIdentifier() {
        if (transactionId != null && !transactionId.isBlank()) {
            return transactionId;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        throw new IllegalArgumentException("At least one identifier (transaction_id or email) is required");
    }
}

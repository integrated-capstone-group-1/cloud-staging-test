package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for dispatching a 6-digit email OTP (CONTRACTS.md Section 2.4).
 */
public record OtpRequest(
        @JsonProperty("transaction_id")
        String transactionId,

        @JsonProperty("email")
        String email,

        @JsonProperty("customer_id")
        String customerId,

        @JsonProperty("action")
        String action
) {
    public OtpRequest(String transactionId) {
        this(transactionId, null, null, null);
    }

    public String resolveIdentifier() {
        if (transactionId != null && !transactionId.isBlank()) {
            return transactionId;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        if (customerId != null && !customerId.isBlank()) {
            return customerId;
        }
        throw new IllegalArgumentException("At least one identifier (transaction_id, email, or customer_id) is required");
    }
}

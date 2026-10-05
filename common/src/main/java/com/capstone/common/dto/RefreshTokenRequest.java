package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to refresh an access token using a long-lived refresh token.
 * Implements token rotation: old refresh token is blacklisted, new one issued.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        @JsonProperty("refresh_token")
        String refreshToken
) {
}

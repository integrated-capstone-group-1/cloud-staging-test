package com.capstone.common.dto;

/**
 * Response from token refresh endpoint.
 * Contains new access token and rotated refresh token.
 */
public record RefreshTokenResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        long refreshExpiresInSeconds
) {
    public static RefreshTokenResponse of(String token, long expiresInSeconds, 
                                          String refreshToken, long refreshExpiresInSeconds) {
        return new RefreshTokenResponse(token, "Bearer", expiresInSeconds, refreshToken, refreshExpiresInSeconds);
    }
}

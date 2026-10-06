package com.capstone.common.dto;

public record PinVerifyResponse(
        String status,
        boolean verified
) {
    public static PinVerifyResponse authorized() {
        return new PinVerifyResponse("AUTHORIZED", true);
    }
}

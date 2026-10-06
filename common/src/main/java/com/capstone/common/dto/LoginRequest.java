
package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password is required")
        String password,

        @JsonProperty("device_id")
        String deviceId
) {
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}


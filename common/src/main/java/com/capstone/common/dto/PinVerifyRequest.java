package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PinVerifyRequest(
        @NotBlank(message = "Customer ID is required")
        @JsonProperty("customer_id")
        String customerId,

        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "^[0-9]{6}$", message = "PIN must be exactly 6 numeric digits")
        String pin
) {
}

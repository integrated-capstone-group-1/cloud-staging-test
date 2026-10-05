package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CreateHoldRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
        @JsonProperty("amount")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @JsonProperty("currency")
        String currency,

        @JsonProperty("hold_type")
        String holdType,

        @JsonProperty("expires_in_hours")
        Integer expiresInHours,

        @JsonProperty("reason")
        String reason
) {
}

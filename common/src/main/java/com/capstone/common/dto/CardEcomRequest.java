package com.capstone.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO to enable/disable e-commerce transactions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardEcomRequest {
    @NotNull(message = "ecom_enabled is required")
    @Min(value = 0, message = "ecom_enabled must be 0 or 1")
    @Max(value = 1, message = "ecom_enabled must be 0 or 1")
    private Integer ecomEnabled; // 0 = disabled, 1 = enabled
}
package com.capstone.common.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO to update card status (freeze/unfreeze).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardStatusRequest {
    @NotNull(message = "Status is required")
    private String status; // ACTIVE, FROZEN
}
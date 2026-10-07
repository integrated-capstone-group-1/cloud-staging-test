package com.capstone.transaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for reporting a suspicious transaction (DIG-91).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspiciousReportRequest {

    @NotBlank(message = "transaction_id is required")
    @JsonProperty("transaction_id")
    private String transactionId;

    @NotBlank(message = "reason is required")
    @JsonProperty("reason")
    private String reason;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("customer_id")
    private String customerId;
}


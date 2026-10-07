package com.capstone.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response payload confirming creation of an admin case for suspicious transaction (DIG-91).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuspiciousReportResponse {

    @JsonProperty("case_id")
    private String caseId;

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("message")
    private String message;

    @JsonProperty("customer_notified")
    private boolean customerNotified;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}


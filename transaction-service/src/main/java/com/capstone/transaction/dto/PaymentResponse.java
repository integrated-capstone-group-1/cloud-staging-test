package com.capstone.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("core_reference_id")
    private String coreReferenceId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("stage")
    private String stage;

    @JsonProperty("paid_amount")
    private BigDecimal paidAmount;

    @JsonProperty("paid_currency")
    private String paidCurrency;

    @JsonProperty("action_required")
    private String actionRequired;

    @JsonProperty("message")
    private String message;

    @JsonProperty("hold_id")
    private String holdId;

    @JsonProperty("review_queue")
    private String reviewQueue;

    @JsonProperty("expires_in_seconds")
    private Integer expiresInSeconds;

    @JsonProperty("funding_breakdown")
    private FundingBreakdownDto fundingBreakdown;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}

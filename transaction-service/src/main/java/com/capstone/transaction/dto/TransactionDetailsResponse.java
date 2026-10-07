package com.capstone.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detailed transaction response with simplified stage timeline and audit trail link (DIG-18).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionDetailsResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("stage")
    private String stage;

    @JsonProperty("txn_type")
    private String txnType;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("debit_account_id")
    private String debitAccountId;

    @JsonProperty("credit_account_id")
    private String creditAccountId;

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("core_reference_id")
    private String coreReferenceId;

    @JsonProperty("audit_ref")
    private String auditRef;

    @JsonProperty("audit_trail_url")
    private String auditTrailUrl;

    @JsonProperty("cancel_reason")
    private String cancelReason;

    @JsonProperty("reversal_of_txn_id")
    private String reversalOfTxnId;

    @JsonProperty("is_cross_currency")
    private String isCrossCurrency;

    @JsonProperty("fx_rate")
    private BigDecimal fxRate;

    @JsonProperty("dest_amount")
    private BigDecimal destAmount;

    @JsonProperty("dest_currency_code")
    private String destCurrencyCode;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;

    @JsonProperty("stages")
    private List<StageTimelineDto> stages;
}

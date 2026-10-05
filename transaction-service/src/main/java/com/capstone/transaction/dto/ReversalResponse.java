package com.capstone.transaction.dto;

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
public class ReversalResponse {

    @JsonProperty("reversal_txn_id")
    private String reversalTxnId;

    @JsonProperty("original_txn_id")
    private String originalTxnId;

    @JsonProperty("core_reference_id")
    private String coreReferenceId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("reversed_amount")
    private BigDecimal reversedAmount;

    @JsonProperty("reversed_currency")
    private String reversedCurrency;

    @JsonProperty("settled_at")
    private LocalDateTime settledAt;
}

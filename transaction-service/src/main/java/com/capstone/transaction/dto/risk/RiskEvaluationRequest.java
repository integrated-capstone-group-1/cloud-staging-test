package com.capstone.transaction.dto.risk;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvaluationRequest {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("customer_id")
    private String customerId;

    private BigDecimal amount;

    private String currency;

    @JsonProperty("ip_address")
    private String ipAddress;

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("target_currency")
    private String targetCurrency;
}

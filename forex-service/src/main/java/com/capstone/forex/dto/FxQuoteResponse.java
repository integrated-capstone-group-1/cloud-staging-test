package com.capstone.forex.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxQuoteResponse {

    @JsonProperty("quote_id")
    private String quoteId;

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("source_currency")
    private String sourceCurrency;

    @JsonProperty("target_currency")
    private String targetCurrency;

    @JsonProperty("target_amount")
    private BigDecimal targetAmount;

    @JsonProperty("spot_rate")
    private BigDecimal spotRate;

    @JsonProperty("spread_percentage")
    private BigDecimal spreadPercentage;

    @JsonProperty("fee_source_amount")
    private BigDecimal feeSourceAmount;

    @JsonProperty("total_source_amount")
    private BigDecimal totalSourceAmount;

    @JsonProperty("valid_until")
    private OffsetDateTime validUntil;

    @JsonProperty("expires_in_seconds")
    private int expiresInSeconds;
}

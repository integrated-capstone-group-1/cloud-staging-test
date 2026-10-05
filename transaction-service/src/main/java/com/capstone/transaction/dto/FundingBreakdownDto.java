package com.capstone.transaction.dto;

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
public class FundingBreakdownDto {

    @JsonProperty("direct_debited_amount")
    private BigDecimal directDebitedAmount;

    @JsonProperty("direct_debited_currency")
    private String directDebitedCurrency;

    @JsonProperty("converted_shortfall_amount")
    private BigDecimal convertedShortfallAmount;

    @JsonProperty("funding_debited_amount")
    private BigDecimal fundingDebitedAmount;

    @JsonProperty("funding_debited_currency")
    private String fundingDebitedCurrency;

    @JsonProperty("fx_fee_amount")
    private BigDecimal fxFeeAmount;

    @JsonProperty("fx_rate_applied")
    private BigDecimal fxRateApplied;
}

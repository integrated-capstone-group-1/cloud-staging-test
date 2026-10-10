package com.capstone.transaction.dto;

import com.capstone.common.util.MonetaryAmountDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    @NotBlank(message = "recipient_account_id is required")
    @JsonProperty("recipient_account_id")
    private String recipientAccountId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
    @Digits(integer = 14, fraction = 4, message = "amount scale cannot exceed 4 decimal places")
    @JsonDeserialize(using = MonetaryAmountDeserializer.class)
    @JsonProperty("amount")
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @JsonProperty("currency")
    private String currency;

    @JsonProperty("funding_pocket_id")
    private String fundingPocketId;

    @JsonProperty("quote_id")
    private String quoteId;

    @JsonProperty("sender_account_id")
    private String senderAccountId;
}

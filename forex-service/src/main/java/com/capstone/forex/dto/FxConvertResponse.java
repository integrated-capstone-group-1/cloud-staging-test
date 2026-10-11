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
public class FxConvertResponse {

    @JsonProperty("conversion_id")
    private String conversionId;

    @JsonProperty("quote_id")
    private String quoteId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("source_pocket_id")
    private String sourcePocketId;

    @JsonProperty("target_pocket_id")
    private String targetPocketId;

    @JsonProperty("source_amount_debited")
    private BigDecimal sourceAmountDebited;

    @JsonProperty("target_amount_credited")
    private BigDecimal targetAmountCredited;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
}

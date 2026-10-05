package com.capstone.forex.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FxConvertRequest {

    @NotBlank(message = "quote_id is required")
    @JsonProperty("quote_id")
    private String quoteId;

    @NotBlank(message = "source_pocket_id is required")
    @JsonProperty("source_pocket_id")
    private String sourcePocketId;

    @NotBlank(message = "target_pocket_id is required")
    @JsonProperty("target_pocket_id")
    private String targetPocketId;
}

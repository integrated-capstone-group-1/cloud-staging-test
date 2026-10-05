package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CoreSettlementRequest(
        @NotBlank(message = "posting_reference_id is required")
        @JsonProperty("posting_reference_id")
        String postingReferenceId,

        @JsonProperty("txn_type")
        String txnType,

        @JsonProperty("capture_hold_id")
        String captureHoldId,

        @NotEmpty(message = "legs must not be empty")
        @Valid
        @JsonProperty("legs")
        List<SettlementLegDto> legs
) {
}

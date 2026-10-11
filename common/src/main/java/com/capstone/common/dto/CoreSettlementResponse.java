package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record CoreSettlementResponse(
        @JsonProperty("posting_reference_id")
        String postingReferenceId,

        @JsonProperty("core_reference_id")
        String coreReferenceId,

        @JsonProperty("status")
        String status,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        @JsonProperty("settled_at")
        LocalDateTime settledAt
) {
}

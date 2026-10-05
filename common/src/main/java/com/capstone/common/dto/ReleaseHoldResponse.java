package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReleaseHoldResponse(
        @JsonProperty("hold_id")
        String holdId,

        @JsonProperty("status")
        String status
) {
}

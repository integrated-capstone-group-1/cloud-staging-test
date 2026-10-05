package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record HoldResponse(
        @JsonProperty("hold_id")
        String holdId,

        @JsonProperty("status")
        String status,

        @JsonProperty("held_amount")
        BigDecimal heldAmount
) {
}

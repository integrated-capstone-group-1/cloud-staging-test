package com.capstone.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record LegDTO(
        @JsonProperty("leg_no") int leg_no,
        @JsonProperty("account_id") String account_id,
        @JsonProperty("direction") String direction,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency
) {}

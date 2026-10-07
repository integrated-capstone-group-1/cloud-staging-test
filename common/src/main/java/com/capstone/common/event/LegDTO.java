package com.capstone.common.event;

import java.math.BigDecimal;

public record LegDTO(
        int leg_no,
        String account_id,
        String direction,
        BigDecimal amount,
        String currency
) {}

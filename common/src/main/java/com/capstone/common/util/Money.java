package com.capstone.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Standard monetary representation matching CONTRACTS.md Section 1.1:
 * - NUMBER(18, 4) with scale 4
 * - Banker's rounding: RoundingMode.HALF_EVEN
 */
public final class Money {

    public static final int DEFAULT_SCALE = 4;
    public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

    private Money() {}

    public static BigDecimal of(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        }
        return amount.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    public static BigDecimal of(String amount) {
        if (amount == null || amount.isBlank()) {
            return BigDecimal.ZERO.setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
        }
        return new BigDecimal(amount).setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    public static BigDecimal of(double amount) {
        return BigDecimal.valueOf(amount).setScale(DEFAULT_SCALE, DEFAULT_ROUNDING);
    }

    public static String format(BigDecimal amount) {
        return of(amount).toPlainString();
    }
}


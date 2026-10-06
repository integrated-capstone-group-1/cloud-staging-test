package com.capstone.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    @DisplayName("DIG-63: Money sets scale 4 and uses HALF_EVEN rounding")
    void testMoneyScaleAndRounding() {
        BigDecimal val = Money.of(new BigDecimal("50.12345"));
        // 50.12345 with HALF_EVEN rounds 45 to 4 (since 4 is even) -> 50.1234
        assertThat(val.scale()).isEqualTo(4);
        assertThat(val.toString()).isEqualTo("50.1234");

        BigDecimal val2 = Money.of(new BigDecimal("50.12355"));
        // 50.12355 with HALF_EVEN rounds 55 to 6 (since 6 is even) -> 50.1236
        assertThat(val2.scale()).isEqualTo(4);
        assertThat(val2.toString()).isEqualTo("50.1236");
    }

    @Test
    @DisplayName("DIG-63: Money.of handles String and double inputs with scale 4")
    void testMoneyStringAndDouble() {
        assertThat(Money.of("100").toString()).isEqualTo("100.0000");
        assertThat(Money.of(25.5).toString()).isEqualTo("25.5000");
        assertThat(Money.format(new BigDecimal("12.34"))).isEqualTo("12.3400");
    }
}


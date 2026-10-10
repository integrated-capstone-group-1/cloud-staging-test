package com.capstone.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonetaryAmountDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class TestDto {
        @JsonDeserialize(using = MonetaryAmountDeserializer.class)
        public BigDecimal amount;
    }

    @Test
    @DisplayName("Accepts valid scale <= 4 decimal amounts")
    void shouldAcceptValidScaleAmounts() throws Exception {
        TestDto dto1 = mapper.readValue("{\"amount\": \"100.0000\"}", TestDto.class);
        assertThat(dto1.amount).isEqualByComparingTo("100.0000");

        TestDto dto2 = mapper.readValue("{\"amount\": \"50.25\"}", TestDto.class);
        assertThat(dto2.amount).isEqualByComparingTo("50.25");

        TestDto dto3 = mapper.readValue("{\"amount\": \"10\"}", TestDto.class);
        assertThat(dto3.amount).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("Rejects scale > 4 decimals")
    void shouldRejectScaleExceeding4() {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\": \"10.123456\"}", TestDto.class))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale at most 4");
    }

    @Test
    @DisplayName("Rejects scientific notation")
    void shouldRejectScientificNotation() {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\": \"1e3\"}", TestDto.class))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scientific notation");
    }

    @Test
    @DisplayName("Rejects non-numeric strings and negative amounts")
    void shouldRejectNonNumericAndNegative() {
        assertThatThrownBy(() -> mapper.readValue("{\"amount\": \"abc\"}", TestDto.class))
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> mapper.readValue("{\"amount\": \"-10.00\"}", TestDto.class))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}

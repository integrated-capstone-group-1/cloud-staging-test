package com.capstone.common.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Custom Jackson deserializer enforcing strict fixed-point decimal monetary representation.
 * - Enforces scale at most 4 digits.
 * - Strictly rejects scientific notation (e.g. "1e3", "1E4").
 * - Strictly rejects negative numbers or non-numeric strings.
 * Enforces docs/CONTRACTS.md line 11 &amp; line 163 and Oracle F8.
 */
public class MonetaryAmountDeserializer extends JsonDeserializer<BigDecimal> {

    private static final Pattern FIXED_POINT_PATTERN = Pattern.compile("^[0-9]+(\\.[0-9]{1,4})?$");

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.trim();
        if (!FIXED_POINT_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    "Invalid monetary amount format: must be a positive decimal string with scale at most 4 and without scientific notation");
        }
        return new BigDecimal(text);
    }
}

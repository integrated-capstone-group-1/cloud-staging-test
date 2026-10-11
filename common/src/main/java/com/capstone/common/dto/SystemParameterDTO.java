package com.capstone.common.dto;

import java.math.BigDecimal;

public record SystemParameterDTO(
        String paramKey,
        String paramDescription,
        BigDecimal paramValueNumeric,
        String paramValueText,
        Integer paramVersion
) {}

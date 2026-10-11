package com.capstone.forex.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FxRateId implements Serializable {
    private String baseCurrency;
    private String quoteCurrency;
}

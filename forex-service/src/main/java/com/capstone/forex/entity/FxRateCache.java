package com.capstone.forex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Entity mapping to Oracle XE table `fx_rate` (LEDGER_APP / FX schema).
 * Composite Primary Key: (base_currency, quote_currency).
 */
@Entity
@Table(name = "fx_rate")
@IdClass(FxRateId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FxRateCache {

    @Id
    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency;

    @Id
    @Column(name = "quote_currency", nullable = false, length = 3)
    private String quoteCurrency;

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(name = "source", nullable = false, length = 50)
    private String source;
}

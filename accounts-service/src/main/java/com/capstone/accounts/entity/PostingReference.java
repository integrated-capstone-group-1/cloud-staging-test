package com.capstone.accounts.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps to POSTING_REFERENCE (Oracle XE 21c).
 * Owned exclusively by accounts-service for CBS settlement deduplication and sweeper inquiry.
 */
@Entity
@Table(name = "posting_reference")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostingReference {

    @Id
    @Column(name = "posting_reference_id", length = 36, nullable = false, updatable = false)
    private String postingReferenceId;

    @Column(name = "core_reference_id", length = 50, nullable = false, unique = true)
    private String coreReferenceId;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "txn_type", length = 30, nullable = false)
    private String txnType;

    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private String status = "SETTLED";

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;
}

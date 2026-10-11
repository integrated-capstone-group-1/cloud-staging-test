package com.capstone.transaction.model;

import com.capstone.common.dto.TransactionResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Encapsulates the cached idempotency state in Redis.
 * Stored under key: idempotency:{key} with a 24-hour TTL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdempotencyRecord(
        String idempotencyKey,
        String requestHash,
        String status,
        TransactionResponse response,
        Instant createdAt
) {
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    public static IdempotencyRecord inProgress(String key, String requestHash) {
        return new IdempotencyRecord(key, requestHash, STATUS_PROCESSING, null, Instant.now());
    }

    public static IdempotencyRecord completed(String key, String requestHash, TransactionResponse response) {
        return new IdempotencyRecord(key, requestHash, STATUS_COMPLETED, response, Instant.now());
    }
}

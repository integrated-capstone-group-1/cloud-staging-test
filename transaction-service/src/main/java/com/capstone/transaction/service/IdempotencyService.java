
package com.capstone.transaction.service;

import com.capstone.common.constants.RedisKeys;
import com.capstone.common.dto.TransactionResponse;
import com.capstone.common.exception.IdempotencyConflictException;
import com.capstone.transaction.metrics.BankingMetricsService;
import com.capstone.transaction.model.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis-backed idempotency guard. Keys: idempotency:{key}, TTL 24 h.
 */
@Service
@Slf4j
public class IdempotencyService {

    private static final Duration TTL               = Duration.ofHours(24);
    private static final String   PROCESSING_MARKER = "PROCESSING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;
    private final BankingMetricsService metricsService;

    @Autowired
    public IdempotencyService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BankingMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public IdempotencyService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, null);
    }

    public String computePayloadHash(Object payload) {
        if (payload == null) {
            return "";
        }
        try {
            byte[] bytes = (payload instanceof byte[] b)
                    ? b
                    : (payload instanceof String s)
                    ? s.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256 payload hash", e);
        }
    }

    public Optional<TransactionResponse> getCached(String idempotencyKey) {
        return getCached(idempotencyKey, null);
    }

    public Optional<TransactionResponse> getCached(String idempotencyKey, String requestHash) {
        String raw = redisTemplate.opsForValue().get(RedisKeys.idempotencyKey(idempotencyKey));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        IdempotencyRecord record = parseRecord(raw);
        if (record == null) {
            return Optional.empty();
        }

        if (requestHash != null && record.requestHash() != null && !record.requestHash().equals(requestHash)) {
            if (metricsService != null) {
                metricsService.recordIdempotencyConflict();
            }
            throw new IdempotencyConflictException(
                    "PAYLOAD_MISMATCH: Idempotency key '" + idempotencyKey + "' was previously used with a different request payload");
        }

        if (IdempotencyRecord.STATUS_PROCESSING.equals(record.status())) {
            if (metricsService != null) {
                metricsService.recordIdempotencyConflict();
            }
            throw new IdempotencyConflictException(
                    "REQUEST_IN_PROGRESS: Request with idempotency key '" + idempotencyKey + "' is already being processed");
        }

        if (IdempotencyRecord.STATUS_COMPLETED.equals(record.status())) {
            return Optional.ofNullable(record.response());
        }

        return Optional.empty();
    }

    public boolean tryLock(String idempotencyKey) {
        return tryLock(idempotencyKey, null);
    }

    public boolean tryLock(String idempotencyKey, String requestHash) {
        IdempotencyRecord inProgressRecord = IdempotencyRecord.inProgress(idempotencyKey, requestHash);
        String json;
        try {
            json = objectMapper.writeValueAsString(inProgressRecord);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize in-progress idempotency record", e);
        }

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.idempotencyKey(idempotencyKey), json, TTL);

        if (Boolean.TRUE.equals(acquired)) {
            return true;
        }

        String existingRaw = redisTemplate.opsForValue().get(RedisKeys.idempotencyKey(idempotencyKey));
        if (existingRaw != null && !existingRaw.isBlank()) {
            IdempotencyRecord existingRecord = parseRecord(existingRaw);
            if (existingRecord != null) {
                if (requestHash != null && existingRecord.requestHash() != null
                        && !existingRecord.requestHash().equals(requestHash)) {
                    if (metricsService != null) {
                        metricsService.recordIdempotencyConflict();
                    }
                    throw new IdempotencyConflictException(
                            "PAYLOAD_MISMATCH: Idempotency key '" + idempotencyKey + "' was previously used with a different request payload");
                }

                if (IdempotencyRecord.STATUS_PROCESSING.equals(existingRecord.status())) {
                    if (metricsService != null) {
                        metricsService.recordIdempotencyConflict();
                    }
                    throw new IdempotencyConflictException(
                            "REQUEST_IN_PROGRESS: Request with idempotency key '" + idempotencyKey + "' is already being processed");
                }

                if (IdempotencyRecord.STATUS_COMPLETED.equals(existingRecord.status())) {
                    return false;
                }
            }
        }

        if (metricsService != null) {
            metricsService.recordIdempotencyConflict();
        }
        throw new IdempotencyConflictException(
                "REQUEST_IN_PROGRESS: Request with idempotency key '" + idempotencyKey + "' is already being processed");
    }

    public void storeResult(String idempotencyKey, TransactionResponse response) {
        storeResult(idempotencyKey, null, response);
    }

    public void storeResult(String idempotencyKey, String requestHash, TransactionResponse response) {
        try {
            IdempotencyRecord record = IdempotencyRecord.completed(idempotencyKey, requestHash, response);
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(RedisKeys.idempotencyKey(idempotencyKey), json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent transaction result", e);
        }
    }

    /** Called on any exception path so the key does not permanently block retries */
    public void release(String idempotencyKey) {
        redisTemplate.delete(RedisKeys.idempotencyKey(idempotencyKey));
    }

    private IdempotencyRecord parseRecord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (PROCESSING_MARKER.equals(raw)) {
            return IdempotencyRecord.inProgress(null, null);
        }
        try {
            if (raw.contains("\"status\"") || raw.contains("\"requestHash\"")) {
                return objectMapper.readValue(raw, IdempotencyRecord.class);
            }
            TransactionResponse legacyResponse = objectMapper.readValue(raw, TransactionResponse.class);
            return IdempotencyRecord.completed(null, null, legacyResponse);
        } catch (Exception e) {
            log.debug("Failed to parse idempotency entry JSON: {}", raw, e);
            return null;
        }
    }
}


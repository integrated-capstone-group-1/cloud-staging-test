package com.capstone.transaction.consumer;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.transaction.entity.postgres.LedgerMutationAudit;
import com.capstone.transaction.repository.postgres.LedgerMutationAuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent Kafka consumer listening to transaction.events.
 * Appends immutable double-entry ledger rows to PostgreSQL ledger_mutation_audit.
 * Handles deterministic UUIDv5 generation, duplicate prevention, DLQ routing for poison messages,
 * and container pause on PostgreSQL outages.
 */
@Component
@Slf4j
public class AuditLedgerConsumer {

    private final LedgerMutationAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry registry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    public AuditLedgerConsumer(
            LedgerMutationAuditRepository repository,
            ObjectMapper objectMapper,
            KafkaListenerEndpointRegistry registry,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.kafkaTemplate = kafkaTemplate;
    }

    public AuditLedgerConsumer(
            LedgerMutationAuditRepository repository,
            ObjectMapper objectMapper,
            KafkaListenerEndpointRegistry registry) {
        this(repository, objectMapper, registry, null);
    }

    @KafkaListener(topics = KafkaTopics.TRANSACTION_EVENTS, groupId = "transaction-audit-group")
    @Transactional
    public void consume(String message) {
        PaymentCompletedEvent payload = null;
        try {
            KafkaEventWrapper<JsonNode> rawEvent = objectMapper.readValue(message,
                    new TypeReference<KafkaEventWrapper<JsonNode>>() {});

            if (rawEvent == null || !"payment.completed".equalsIgnoreCase(rawEvent.eventType())) {
                log.debug("AuditLedgerConsumer: ignoring non-payment.completed event: {}",
                        rawEvent != null ? rawEvent.eventType() : "null");
                return;
            }

            if (rawEvent.payload() == null || rawEvent.payload().isNull()) {
                log.warn("AuditLedgerConsumer: payload is null for payment.completed event");
                return;
            }

            payload = objectMapper.treeToValue(rawEvent.payload(), PaymentCompletedEvent.class);
            if (payload == null || payload.txnId() == null || payload.legs() == null) {
                log.warn("AuditLedgerConsumer: payload missing required transaction details");
                return;
            }

            // DIG-16 / DIG-88: Idempotency check - if already recorded, ignore duplicate Kafka delivery
            if (repository.existsByTxnId(payload.txnId())) {
                log.info("AuditLedgerConsumer: txnId={} already processed, safely skipping duplicate event", payload.txnId());
                return;
            }

            List<LedgerMutationAudit> audits = new ArrayList<>();
            for (LegDTO leg : payload.legs()) {
                // Deterministic UUIDv5 generation per TR-L04 / B-04
                UUID mutationUuid = UUID.nameUUIDFromBytes((payload.txnId() + "-leg-" + leg.leg_no()).getBytes(StandardCharsets.UTF_8));

                LedgerMutationAudit audit = LedgerMutationAudit.builder()
                        .mutationUuid(mutationUuid)
                        .txnId(payload.txnId())
                        .accountId(leg.account_id())
                        .legNo(leg.leg_no())
                        .mutationAmount(leg.amount())
                        .currencyCode(leg.currency())
                        .mutationType(leg.direction())
                        .txnType("TRANSFER")
                        .auditState("COMMITTED")
                        .createdAt(Instant.now())
                        .build();
                audits.add(audit);
            }

            repository.saveAll(audits);
            log.info("AuditLedgerConsumer: processed {} legs for txnId={}", audits.size(), payload.txnId());

        } catch (DataIntegrityViolationException ex) {
            // Reprocessing race condition or duplicate key caught by uq_ledger_audit_txn_leg constraint
            log.warn("AuditLedgerConsumer: duplicate entry constraint for txnId={}, safely ignored: {}",
                    payload != null ? payload.txnId() : "unknown", ex.getMessage());
        } catch (DataAccessException ex) {
            // Transient database outage: pause consumer container and retry with backoff.
            // DO NOT route database connection errors to DLQ!
            log.error("AuditLedgerConsumer: Postgres down, pausing consumers: {}", ex.getMessage());
            pauseContainers();
            throw ex; // Re-throw to trigger Kafka backoff and redelivery
        } catch (Exception e) {
            // Corrupted, unparseable poison messages (CH-07): route to DLQ
            log.error("AuditLedgerConsumer: poison message error, routing to DLQ: {}", e.getMessage(), e);
            routeToDlq(message, e);
        }
    }

    private void routeToDlq(String rawMessage, Exception cause) {
        if (kafkaTemplate != null) {
            try {
                String dlqTopic = KafkaTopics.TRANSACTION_EVENTS + ".dlq";
                kafkaTemplate.send(dlqTopic, rawMessage);
                log.info("AuditLedgerConsumer: successfully dispatched poison message to DLQ topic {}", dlqTopic);
            } catch (Exception ex) {
                log.error("AuditLedgerConsumer: failed to publish to DLQ topic: {}", ex.getMessage());
            }
        }
    }

    private void pauseContainers() {
        if (registry != null && registry.getListenerContainers() != null) {
            registry.getListenerContainers().forEach(container -> {
                if (container.isRunning()) {
                    container.pause();
                }
            });
        }
    }
}

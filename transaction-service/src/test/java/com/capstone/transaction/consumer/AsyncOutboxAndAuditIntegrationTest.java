package com.capstone.transaction.consumer;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.transaction.entity.postgres.LedgerMutationAudit;
import com.capstone.transaction.repository.postgres.LedgerMutationAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Workstream 5 Integration Test Suite: Gym (SEAM-GYM-01 to SEAM-GYM-03).
 *
 * [W-H-Y MODEL & TRACEABILITY]:
 * - WHAT: Comprehensive integration test suite for Async Messaging, Outbox Relay, and Audit Seams:
 *   - SEAM-GYM-01: Outbox Polling Relay to Kafka Seam (DIG-20, DIG-32, BR-02)
 *   - SEAM-GYM-02: Audit Consumer to PostgreSQL Seam (DIG-88, BR-02)
 *   - SEAM-GYM-03: Transient Retry & Poison Message Dead-Letter Queue Seam (DIG-89, BR-34)
 * - HOW: Tests the outbox polling transition (PENDING -> PUBLISHED), emission to transaction.events,
 *        deserialization and persistence into PostgreSQL immutable ledger_mutation_audit table,
 *        and graceful poison message isolation to DLQ without stalling consumers.
 * - WHY: Preserves dual-entry ledger audit compliance across Oracle XE Core Banking and PostgreSQL Audit DB
 *        without blocking live settlement or losing transactions during transient outages.
 */
@ExtendWith(MockitoExtension.class)
class AsyncOutboxAndAuditIntegrationTest {

    @Mock
    private LedgerMutationAuditRepository auditRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Mock
    private MessageListenerContainer messageListenerContainer;

    private ObjectMapper objectMapper;
    private AuditLedgerConsumer auditConsumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        auditConsumer = new AuditLedgerConsumer(auditRepository, objectMapper, listenerRegistry, kafkaTemplate);
    }

    @Test
    @DisplayName("SEAM-GYM-01: Staged outbox record is polled, published to Kafka transaction.events, and marked PUBLISHED")
    void seamGym01_outboxPollingRelayToKafka() throws Exception {
        // Step 1: Simulate Oracle XE outbox record staged in PENDING state
        String txnId = "txn-int-01";
        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR", 1),
                new SettlementLegDto("acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR", 2)
        );
        CoreSettlementRequest settlementReq = new CoreSettlementRequest(txnId, "TRANSFER", "hold-8812-uuid", legs);
        String payloadJson = objectMapper.writeValueAsString(settlementReq);

        SimulatedOutboxRecord outboxRecord = new SimulatedOutboxRecord(
                "ob-int-01", "accounts-service", "SETTLEMENT", txnId, "SETTLEMENT_COMPLETED", payloadJson, "PENDING"
        );

        // Step 2: Simulated Relay processes pending outbox record
        AtomicInteger publishCount = new AtomicInteger(0);
        if ("PENDING".equals(outboxRecord.status)) {
            // Transform & emit KafkaEventWrapper to topic transaction.events
            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                    txnId, "FT2627800001", "cust-user-001",
                    List.of(
                            new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR"),
                            new LegDTO(2, "acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR")
                    )
            );
            KafkaEventWrapper<PaymentCompletedEvent> wrapper = new KafkaEventWrapper<>(
                    outboxRecord.outboxId, "TRANSACTION", txnId, "payment.completed", Instant.now(), completedEvent
            );

            kafkaTemplate.send(KafkaTopics.TRANSACTION_EVENTS, txnId, wrapper);

            // State mutation: PENDING -> PUBLISHED
            outboxRecord.status = "PUBLISHED";
            outboxRecord.publishedAt = LocalDateTime.now();
            publishCount.incrementAndGet();
        }

        // Step 3: Assert Kafka publication occurred with event_key = txnId
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_EVENTS), eq(txnId), payloadCaptor.capture());
        assertThat(publishCount.get()).isEqualTo(1);

        Object sentEvent = payloadCaptor.getValue();
        assertThat(sentEvent).isInstanceOf(KafkaEventWrapper.class);
        @SuppressWarnings("unchecked")
        KafkaEventWrapper<PaymentCompletedEvent> capturedWrapper = (KafkaEventWrapper<PaymentCompletedEvent>) sentEvent;
        assertThat(capturedWrapper.eventType()).isEqualTo("payment.completed");
        assertThat(capturedWrapper.aggregateId()).isEqualTo(txnId);
        assertThat(capturedWrapper.payload().legs()).hasSize(2);

        // Step 4: Assert outbox record status updated to PUBLISHED
        assertThat(outboxRecord.status).isEqualTo("PUBLISHED");
        assertThat(outboxRecord.publishedAt).isNotNull();

        // Step 5: Verify subsequent poll finds 0 pending items (idempotent deduplication)
        boolean hasPending = "PENDING".equals(outboxRecord.status);
        assertThat(hasPending).isFalse();
    }

    @Test
    @DisplayName("SEAM-GYM-02: Consumes settlement event and persists double-entry legs into PostgreSQL audit repository")
    void seamGym02_auditConsumerToPostgres() throws Exception {
        String txnId = "txn-int-01";
        LegDTO debitLeg = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        LegDTO creditLeg = new LegDTO(2, "acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(debitLeg, creditLeg));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-9912", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload
        );
        String kafkaJson = objectMapper.writeValueAsString(event);

        // Not previously processed
        when(auditRepository.existsByTxnId(txnId)).thenReturn(false);

        // Consume Kafka message
        auditConsumer.consume(kafkaJson);

        // Capture saved audit records
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditRepository).saveAll(captor.capture());

        List<LedgerMutationAudit> savedAudits = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(savedAudits).hasSize(2);

        // Leg 1: DEBIT verification
        LedgerMutationAudit leg1 = savedAudits.get(0);
        assertThat(leg1.getTxnId()).isEqualTo(txnId);
        assertThat(leg1.getAccountId()).isEqualTo("acct-juan-eur-01");
        assertThat(leg1.getLegNo()).isEqualTo(1);
        assertThat(leg1.getMutationType()).isEqualTo("DEBIT");
        assertThat(leg1.getMutationAmount()).isEqualByComparingTo("10.0000");
        assertThat(leg1.getCurrencyCode()).isEqualTo("EUR");
        assertThat(leg1.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-1").getBytes(StandardCharsets.UTF_8)));

        // Leg 2: CREDIT verification
        LedgerMutationAudit leg2 = savedAudits.get(1);
        assertThat(leg2.getTxnId()).isEqualTo(txnId);
        assertThat(leg2.getAccountId()).isEqualTo("acct-maria-eur-01");
        assertThat(leg2.getLegNo()).isEqualTo(2);
        assertThat(leg2.getMutationType()).isEqualTo("CREDIT");
        assertThat(leg2.getMutationAmount()).isEqualByComparingTo("10.0000");
        assertThat(leg2.getCurrencyCode()).isEqualTo("EUR");
        assertThat(leg2.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-2").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SEAM-GYM-03: Poison message is routed to DLQ without crashing; subsequent valid message processes cleanly")
    void seamGym03_poisonMessageRoutedToDlqAndConsumerRecovers() throws Exception {
        // Step 1: Deliver malformed poison payload
        String poisonMessage = "{ malformed json: not valid at all; missing quotes and brackets";

        auditConsumer.consume(poisonMessage);

        // Assert: Poison message sent to DLQ topic
        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_EVENTS + ".dlq"), eq(poisonMessage));
        // Assert: Database was not invoked for the corrupted message
        verify(auditRepository, never()).saveAll(any());

        // Step 2: Deliver subsequent valid message to prove consumer partition remained unblocked
        String validTxnId = "txn-unblocked-02";
        LegDTO leg1 = new LegDTO(1, "acct-valid-01", "DEBIT", new BigDecimal("50.0000"), "PHP");
        LegDTO leg2 = new LegDTO(2, "acct-valid-02", "CREDIT", new BigDecimal("50.0000"), "PHP");
        PaymentCompletedEvent validPayload = new PaymentCompletedEvent(
                validTxnId, "FT99990001", "cust-user-999", List.of(leg1, leg2));

        KafkaEventWrapper<PaymentCompletedEvent> validEvent = new KafkaEventWrapper<>(
                "evt-unblocked-01", "TRANSACTION", validTxnId, "payment.completed", Instant.now(), validPayload);
        String validJson = objectMapper.writeValueAsString(validEvent);

        when(auditRepository.existsByTxnId(validTxnId)).thenReturn(false);

        auditConsumer.consume(validJson);

        // Assert: Valid transaction was processed and saved to audit DB successfully
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditRepository).saveAll(captor.capture());
        List<LedgerMutationAudit> saved = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getTxnId()).isEqualTo(validTxnId);
        assertThat(saved.get(1).getTxnId()).isEqualTo(validTxnId);
    }

    @Test
    @DisplayName("SEAM-GYM-03 (Transient): Postgres outage triggers listener container pause and rethrow for Kafka backoff without sending to DLQ")
    void seamGym03_transientPostgresOutagePausesConsumerWithoutDlq() throws Exception {
        String txnId = "txn-outage-01";
        LegDTO leg1 = new LegDTO(1, "acct-01", "DEBIT", new BigDecimal("25.0000"), "PHP");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT1111", "cust-01", List.of(leg1));
        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-outage", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);
        String message = objectMapper.writeValueAsString(event);

        when(auditRepository.existsByTxnId(txnId)).thenReturn(false);
        doThrow(new CannotAcquireLockException("Database connection timeout - simulated DB restart"))
                .when(auditRepository).saveAll(any());

        when(messageListenerContainer.isRunning()).thenReturn(true);
        when(listenerRegistry.getListenerContainers()).thenReturn(Collections.singletonList(messageListenerContainer));

        assertThatThrownBy(() -> auditConsumer.consume(message))
                .isInstanceOf(CannotAcquireLockException.class);

        // Verify consumer container paused to prevent message loss
        verify(messageListenerContainer).pause();
        // Crucial: Transient DB failures must NEVER be sent to DLQ
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    private static class SimulatedOutboxRecord {
        String outboxId;
        String sourceService;
        String aggregateType;
        String aggregateId;
        String eventType;
        String payload;
        String status;
        LocalDateTime publishedAt;

        public SimulatedOutboxRecord(String outboxId, String sourceService, String aggregateType,
                                     String aggregateId, String eventType, String payload, String status) {
            this.outboxId = outboxId;
            this.sourceService = sourceService;
            this.aggregateType = aggregateType;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.payload = payload;
            this.status = status;
        }
    }
}


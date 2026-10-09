package com.capstone.transaction.seam;

import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.transaction.consumer.AuditLedgerConsumer;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEAM-GYM-02: Audit Consumer to PostgreSQL Seam Test
 *
 * Validates that:
 * 1. Settlement events consumed from Kafka `transaction.events` are deserialized correctly
 * 2. Double-entry ledger legs are persisted to PostgreSQL `ledger_mutation_audit` as immutable rows
 * 3. Deterministic UUIDv5 hash is generated from (txn_id, leg_no)
 * 4. Debit and credit legs have opposite directions
 * 5. Idempotent deduplication prevents duplicate audit records on replay
 *
 * Mapped to: DIG-88, BR-02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SEAM-GYM-02: Audit Consumer to PostgreSQL")
public class AuditConsumerIntegrationTest {

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
    @DisplayName("SEAM-GYM-02-A: Consumes settlement event and persists double-entry legs to PostgreSQL")
    void shouldConsumeEventAndPersistLegsToAudit() throws Exception {
        // Arrange: Simulate Kafka event with settlement legs
        String txnId = "txn-seam-02-a";
        LegDTO debitLeg = new LegDTO(1, "acct-juan-php-01", "DEBIT", new BigDecimal("2500.0000"), "PHP");
        LegDTO creditLeg = new LegDTO(2, "acct-maria-php-01", "CREDIT", new BigDecimal("2500.0000"), "PHP");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "ref-seam-02-a", "cust-user-001", List.of(debitLeg, creditLeg));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-seam-02-a", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload
        );
        String kafkaJson = objectMapper.writeValueAsString(event);

        // Mock: Repository does not have existing records for this txn
        when(auditRepository.existsByTxnId(txnId)).thenReturn(false);

        // Act: Consume Kafka message
        auditConsumer.consume(kafkaJson);

        // Assert: Captured audit records
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditRepository).saveAll(captor.capture());

        List<LedgerMutationAudit> savedAudits = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(savedAudits).hasSize(2);

        // Leg 1: DEBIT verification
        LedgerMutationAudit leg1 = savedAudits.get(0);
        assertThat(leg1.getTxnId()).isEqualTo(txnId);
        assertThat(leg1.getAccountId()).isEqualTo("acct-juan-php-01");
        assertThat(leg1.getLegNo()).isEqualTo(1);
        assertThat(leg1.getMutationType()).isEqualTo("DEBIT");
        assertThat(leg1.getMutationAmount()).isEqualByComparingTo("2500.0000");
        assertThat(leg1.getCurrencyCode()).isEqualTo("PHP");
        assertThat(leg1.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-1").getBytes(StandardCharsets.UTF_8)));

        // Leg 2: CREDIT verification
        LedgerMutationAudit leg2 = savedAudits.get(1);
        assertThat(leg2.getTxnId()).isEqualTo(txnId);
        assertThat(leg2.getAccountId()).isEqualTo("acct-maria-php-01");
        assertThat(leg2.getLegNo()).isEqualTo(2);
        assertThat(leg2.getMutationType()).isEqualTo("CREDIT");
        assertThat(leg2.getMutationAmount()).isEqualByComparingTo("2500.0000");
        assertThat(leg2.getCurrencyCode()).isEqualTo("PHP");
        assertThat(leg2.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-2").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SEAM-GYM-02-B: Idempotent deduplication prevents duplicate audit on replay")
    void shouldDedupticateOnReplayWithIdenticalKey() throws Exception {
        // Arrange: Same event replayed
        String txnId = "txn-seam-02-b";
        PaymentCompletedEvent payload = new PaymentCompletedEvent(
                txnId, "ref-seam-02-b", "cust-user-001",
                List.of(
                        new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("50.0000"), "EUR"),
                        new LegDTO(2, "acct-maria-eur-01", "CREDIT", new BigDecimal("50.0000"), "EUR")
                )
        );
        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-seam-02-b", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload
        );
        String kafkaJson = objectMapper.writeValueAsString(event);

        // Mock: Repository already has this txn (replay detected)
        when(auditRepository.existsByTxnId(txnId)).thenReturn(true);

        // Act: Attempt to consume same event again
        auditConsumer.consume(kafkaJson);

        // Assert: No new audit records persisted (idempotent skip)
        verify(auditRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("SEAM-GYM-02-C: Multi-leg settlement with >2 legs persists all legs with correct leg_no sequence")
    void shouldPersistMultiLegSettlementWithCorrectSequence() throws Exception {
        // Arrange: 4-leg settlement (multi-currency transfer with fee)
        String txnId = "txn-seam-02-c";
        List<LegDTO> legs = List.of(
                new LegDTO(1, "acct-juan-php-01", "DEBIT", new BigDecimal("10000.0000"), "PHP"),
                new LegDTO(2, "acct-fx-bank-01", "CREDIT", new BigDecimal("10000.0000"), "PHP"),
                new LegDTO(3, "acct-fx-bank-01", "DEBIT", new BigDecimal("166.6667"), "EUR"),
                new LegDTO(4, "acct-maria-eur-01", "CREDIT", new BigDecimal("166.6667"), "EUR")
        );
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "ref-seam-02-c", "cust-user-001", legs);

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-seam-02-c", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload
        );
        String kafkaJson = objectMapper.writeValueAsString(event);

        when(auditRepository.existsByTxnId(txnId)).thenReturn(false);

        // Act: Consume multi-leg event
        auditConsumer.consume(kafkaJson);

        // Assert: All 4 legs persisted with correct sequence
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditRepository).saveAll(captor.capture());

        List<LedgerMutationAudit> savedAudits = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(savedAudits).hasSize(4);

        for (int i = 0; i < 4; i++) {
            LedgerMutationAudit audit = savedAudits.get(i);
            assertThat(audit.getTxnId()).isEqualTo(txnId);
            assertThat(audit.getLegNo()).isEqualTo(i + 1);
            assertThat(audit.getMutationUuid())
                    .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-" + (i + 1)).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("SEAM-GYM-02-D: Debit and credit legs have opposite directions with equal amounts")
    void shouldBalanceDebitAndCreditLegs() throws Exception {
        // Arrange: Matched debit and credit pair
        String txnId = "txn-seam-02-d";
        BigDecimal amount = new BigDecimal("500.0000");
        
        PaymentCompletedEvent payload = new PaymentCompletedEvent(
                txnId, "ref-seam-02-d", "cust-user-001",
                List.of(
                        new LegDTO(1, "acct-sender-01", "DEBIT", amount, "USD"),
                        new LegDTO(2, "acct-receiver-01", "CREDIT", amount, "USD")
                )
        );
        
        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-seam-02-d", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload
        );
        String kafkaJson = objectMapper.writeValueAsString(event);

        when(auditRepository.existsByTxnId(txnId)).thenReturn(false);

        // Act: Consume event
        auditConsumer.consume(kafkaJson);

        // Assert: Debit and credit are balanced with equal amounts
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditRepository).saveAll(captor.capture());

        List<LedgerMutationAudit> audits = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(audits).hasSize(2);

        LedgerMutationAudit debit = audits.get(0);
        LedgerMutationAudit credit = audits.get(1);

        assertThat(debit.getMutationType()).isEqualTo("DEBIT");
        assertThat(credit.getMutationType()).isEqualTo("CREDIT");
        assertThat(debit.getMutationAmount()).isEqualByComparingTo(amount);
        assertThat(credit.getMutationAmount()).isEqualByComparingTo(amount);
    }
}

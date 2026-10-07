package com.capstone.transaction.consumer;

import com.capstone.common.constants.KafkaTopics;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditLedgerConsumerTest {

    private LedgerMutationAuditRepository repository;
    private ObjectMapper objectMapper;
    private KafkaListenerEndpointRegistry registry;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private AuditLedgerConsumer consumer;

    @BeforeEach
    void setUp() {
        repository = mock(LedgerMutationAuditRepository.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry = mock(KafkaListenerEndpointRegistry.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        consumer = new AuditLedgerConsumer(repository, objectMapper, registry, kafkaTemplate);
    }

    @Test
    @DisplayName("1. payment.completed event generates corresponding DEBIT and CREDIT rows with correct leg_no")
    void shouldProcessPaymentCompletedEvent_andPersistBothDebitAndCreditLegs() throws Exception {
        String txnId = "txn-5541-uuid";
        LegDTO leg1 = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        LegDTO leg2 = new LegDTO(2, "acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(leg1, leg2));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-7712-uuid", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);

        String message = objectMapper.writeValueAsString(event);

        when(repository.existsByTxnId(txnId)).thenReturn(false);

        consumer.consume(message);

        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());

        List<LedgerMutationAudit> saved = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(saved).hasSize(2);

        // Verify Leg 1 (DEBIT)
        LedgerMutationAudit debitAudit = saved.get(0);
        assertThat(debitAudit.getTxnId()).isEqualTo(txnId);
        assertThat(debitAudit.getAccountId()).isEqualTo("acct-juan-eur-01");
        assertThat(debitAudit.getLegNo()).isEqualTo(1);
        assertThat(debitAudit.getMutationType()).isEqualTo("DEBIT");
        assertThat(debitAudit.getMutationAmount()).isEqualByComparingTo("10.0000");
        assertThat(debitAudit.getCurrencyCode()).isEqualTo("EUR");
        assertThat(debitAudit.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-1").getBytes(StandardCharsets.UTF_8)));

        // Verify Leg 2 (CREDIT)
        LedgerMutationAudit creditAudit = saved.get(1);
        assertThat(creditAudit.getTxnId()).isEqualTo(txnId);
        assertThat(creditAudit.getAccountId()).isEqualTo("acct-maria-eur-01");
        assertThat(creditAudit.getLegNo()).isEqualTo(2);
        assertThat(creditAudit.getMutationType()).isEqualTo("CREDIT");
        assertThat(creditAudit.getMutationAmount()).isEqualByComparingTo("10.0000");
        assertThat(creditAudit.getCurrencyCode()).isEqualTo("EUR");
        assertThat(creditAudit.getMutationUuid())
                .isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-2").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("2a. Reprocessing exact same event skips persistence idempotently via existsByTxnId")
    void shouldIgnoreReprocessedEvent_idempotentlyViaExistsCheck() throws Exception {
        String txnId = "txn-5541-uuid";
        LegDTO leg1 = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(leg1));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-7712-uuid", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);

        String message = objectMapper.writeValueAsString(event);

        // Already exists in PostgreSQL ledger_mutation_audit
        when(repository.existsByTxnId(txnId)).thenReturn(true);

        consumer.consume(message);

        // Verify repository.saveAll was never called on duplicate delivery
        verify(repository, never()).saveAll(any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("2b. Reprocessing with DataIntegrityViolation safely ignores duplicate without rethrow")
    void shouldSafelyIgnoreDuplicateKeyConstraintViolation() throws Exception {
        String txnId = "txn-5541-uuid";
        LegDTO leg1 = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(leg1));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-7712-uuid", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);

        String message = objectMapper.writeValueAsString(event);

        when(repository.existsByTxnId(txnId)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint uq_ledger_audit_txn_leg"))
                .when(repository).saveAll(any());

        // Should not throw exception
        consumer.consume(message);

        // Verify saveAll was attempted, and registry containers were NOT paused
        verify(repository).saveAll(any());
        verify(registry, never()).getListenerContainers();
    }

    @Test
    @DisplayName("3. PostgreSQL connection outage pauses consumers and rethrows for Kafka retry without DLQ")
    void shouldPauseConsumersAndRethrow_onTransientPostgresOutage() throws Exception {
        String txnId = "txn-5541-uuid";
        LegDTO leg1 = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(leg1));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-7712-uuid", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);

        String message = objectMapper.writeValueAsString(event);

        when(repository.existsByTxnId(txnId)).thenReturn(false);
        doThrow(new CannotAcquireLockException("Database connection refused / timeout"))
                .when(repository).saveAll(any());

        MessageListenerContainer mockContainer = mock(MessageListenerContainer.class);
        when(mockContainer.isRunning()).thenReturn(true);
        when(registry.getListenerContainers()).thenReturn(Collections.singletonList(mockContainer));

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(CannotAcquireLockException.class);

        // Verify container was paused to allow DB recovery
        verify(mockContainer).pause();
        // Verify transient DB errors are NEVER routed to DLQ
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    @DisplayName("4. Malformed poison message is routed to DLQ topic without throwing")
    void shouldRoutePoisonMessageToDlq() {
        String poisonMessage = "{ malformed json: not valid at all }";

        consumer.consume(poisonMessage);

        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_EVENTS + ".dlq"), eq(poisonMessage));
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("5. Non-payment.completed events are safely ignored")
    void shouldIgnoreNonPaymentCompletedEvents() throws Exception {
        KafkaEventWrapper<String> event = new KafkaEventWrapper<>(
                "evt-88", "TRANSACTION", "txn-99", "account.created", Instant.now(), "details");
        String message = objectMapper.writeValueAsString(event);

        consumer.consume(message);

        verify(repository, never()).saveAll(any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }
}

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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression Test Suite: REG-GYM-02 (DIG-89, BR-34).
 * Verifies Poison Message Dead-Letter Queue Isolation:
 * - Malformed JSON is detected and safely routed to Dead-Letter Queue (transaction.events.dlq)
 * - Consumer partition is not blocked
 * - Subsequent valid messages continue processing and persisting normally.
 */
class AuditConsumerDlqTest {

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
    @DisplayName("REG-GYM-02: Poison malformed JSON routes to DLQ and subsequent valid message processes cleanly")
    void shouldRouteMalformedMessageToDlq() throws Exception {
        // Step 1: Deliver malformed poison message
        String poisonMessage = "{ malformed json: not valid at all; missing quotes and brackets";

        consumer.consume(poisonMessage);

        // Verify routed directly to transaction.events.dlq topic
        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_EVENTS + ".dlq"), eq(poisonMessage));
        // Verify no repository save occurred for the poison message
        verify(repository, never()).saveAll(any());

        // Step 2: Deliver valid subsequent message to verify consumer is not stuck/blocked
        String validTxnId = "txn-unblocked-9999";
        LegDTO leg1 = new LegDTO(1, "acct-valid-01", "DEBIT", new BigDecimal("50.0000"), "PHP");
        LegDTO leg2 = new LegDTO(2, "acct-valid-02", "CREDIT", new BigDecimal("50.0000"), "PHP");
        PaymentCompletedEvent validPayload = new PaymentCompletedEvent(
                validTxnId, "FT99990001", "cust-user-999", List.of(leg1, leg2));

        KafkaEventWrapper<PaymentCompletedEvent> validEvent = new KafkaEventWrapper<>(
                "evt-9999", "TRANSACTION", validTxnId, "payment.completed", Instant.now(), validPayload);
        String validJson = objectMapper.writeValueAsString(validEvent);

        when(repository.existsByTxnId(validTxnId)).thenReturn(false);

        consumer.consume(validJson);

        // Verify valid event persisted successfully without interruption
        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());

        List<LedgerMutationAudit> saved = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getTxnId()).isEqualTo(validTxnId);
        assertThat(saved.get(1).getTxnId()).isEqualTo(validTxnId);
    }
}


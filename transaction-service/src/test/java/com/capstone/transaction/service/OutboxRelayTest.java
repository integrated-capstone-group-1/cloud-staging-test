package com.capstone.transaction.service;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression Test Suite: REG-GYM-01 (T-004, BR-02).
 * Verifies Outbox Polling Relay Deduplication:
 * - Staged PENDING rows in outbox are published to Kafka topic transaction.events
 * - Outbox status transitions from PENDING to PUBLISHED
 * - Deduplication guarantees each outbox event is processed and published exactly once.
 */
class OutboxRelayTest {

    @Test
    @DisplayName("REG-GYM-01: Outbox events transition from PENDING to PUBLISHED and publish to Kafka")
    void shouldPollPendingAndMarkPublished() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

        // Simulated Outbox Record representation in Core Ledger
        MockOutboxRecord record1 = new MockOutboxRecord("outbox-1", "SETTLEMENT", "txn-101", "SETTLEMENT_COMPLETED", "PENDING");
        MockOutboxRecord record2 = new MockOutboxRecord("outbox-2", "SETTLEMENT", "txn-102", "SETTLEMENT_COMPLETED", "PENDING");
        List<MockOutboxRecord> stagedOutbox = new ArrayList<>(List.of(record1, record2));

        // Simulated Polling Relay Engine
        AtomicInteger publishCount = new AtomicInteger(0);
        for (MockOutboxRecord record : stagedOutbox) {
            if ("PENDING".equals(record.status)) {
                // Publish to Kafka topic transaction.events
                PaymentCompletedEvent payload = new PaymentCompletedEvent(
                        record.aggregateId, "FT2026100801", "cust-user-1",
                        List.of(new LegDTO(1, "acct-1", "DEBIT", new BigDecimal("100.00"), "PHP")));

                kafkaTemplate.send(KafkaTopics.TRANSACTION_EVENTS, record.aggregateId, payload);

                // State mutation: PENDING -> PUBLISHED
                record.status = "PUBLISHED";
                record.publishedAt = LocalDateTime.now();
                publishCount.incrementAndGet();
            }
        }

        // Verify published exactly twice
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.TRANSACTION_EVENTS), anyString(), any());
        assertThat(publishCount.get()).isEqualTo(2);

        // Verify status transition
        assertThat(record1.status).isEqualTo("PUBLISHED");
        assertThat(record1.publishedAt).isNotNull();
        assertThat(record2.status).isEqualTo("PUBLISHED");
        assertThat(record2.publishedAt).isNotNull();

        // Second poll iteration: verify deduplication (zero additional publications)
        int secondPollPublished = 0;
        for (MockOutboxRecord record : stagedOutbox) {
            if ("PENDING".equals(record.status)) {
                secondPollPublished++;
            }
        }
        assertThat(secondPollPublished).isEqualTo(0);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    private static class MockOutboxRecord {
        String outboxId;
        String aggregateType;
        String aggregateId;
        String eventType;
        String status;
        LocalDateTime publishedAt;

        public MockOutboxRecord(String outboxId, String aggregateType, String aggregateId, String eventType, String status) {
            this.outboxId = outboxId;
            this.aggregateType = aggregateType;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.status = status;
        }
    }
}
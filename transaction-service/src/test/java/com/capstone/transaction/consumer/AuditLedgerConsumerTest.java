package com.capstone.transaction.consumer;

import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.transaction.entity.postgres.LedgerMutationAudit;
import com.capstone.transaction.repository.postgres.LedgerMutationAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuditLedgerConsumerTest {

    private final LedgerMutationAuditRepository repository = mock(LedgerMutationAuditRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final AuditLedgerConsumer consumer = new AuditLedgerConsumer(repository, objectMapper, registry);

    @Test
    void shouldProcessPaymentCompletedEvent() throws Exception {
        String txnId = "txn-123";
        LegDTO leg = new LegDTO(1, "acc-1", "DEBIT", new BigDecimal("100.0000"), "PHP");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "ref-1", "cust-1", List.of(leg));
        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-1", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);
        
        String message = objectMapper.writeValueAsString(event);

        consumer.consume(message);

        ArgumentCaptor<Iterable<LedgerMutationAudit>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        
        List<LedgerMutationAudit> saved = (List<LedgerMutationAudit>) captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTxnId()).isEqualTo(txnId);
        // Note: UUID.nameUUIDFromBytes default behavior uses UTF-8, which matches implementation
        assertThat(saved.get(0).getMutationUuid()).isEqualTo(UUID.nameUUIDFromBytes((txnId + "-leg-1").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}

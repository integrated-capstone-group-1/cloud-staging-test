package com.capstone.transaction.consumer;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.transaction.entity.postgres.LedgerMutationAudit;
import com.capstone.transaction.repository.postgres.LedgerMutationAuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditLedgerConsumer {

    private final LedgerMutationAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry registry;

    @KafkaListener(topics = KafkaTopics.TRANSACTION_EVENTS, groupId = "transaction-audit-group")
    @Transactional
    public void consume(String message) {
        try {
            KafkaEventWrapper<PaymentCompletedEvent> event = objectMapper.readValue(message,
                    new TypeReference<KafkaEventWrapper<PaymentCompletedEvent>>() {});

            if (!"payment.completed".equals(event.eventType())) {
                return;
            }

            PaymentCompletedEvent payload = event.payload();
            List<LedgerMutationAudit> audits = new ArrayList<>();

            for (LegDTO leg : payload.legs()) {
                UUID mutationUuid = UUID.nameUUIDFromBytes((payload.txnId() + "-leg-" + leg.leg_no()).getBytes(StandardCharsets.UTF_8));
                
                LedgerMutationAudit audit = LedgerMutationAudit.builder()
                        .mutationUuid(mutationUuid)
                        .txnId(payload.txnId())
                        .accountId(leg.account_id())
                        .mutationAmount(leg.amount())
                        .currencyCode(leg.currency())
                        .mutationType(leg.direction())
                        .txnType("TRANSFER") // Assuming TRANSFER based on context
                        .auditState("COMMITTED")
                        .createdAt(Instant.now())
                        .build();
                audits.add(audit);
            }

            repository.saveAll(audits);
            log.info("AuditLedgerConsumer: processed {} legs for txnId={}", audits.size(), payload.txnId());

        } catch (org.springframework.dao.DataAccessException ex) {
            log.error("AuditLedgerConsumer: Postgres down, pausing consumers: {}", ex.getMessage());
            pauseContainers();
            throw ex; // Re-throw to trigger Kafka retry
        } catch (Exception e) {
            log.error("AuditLedgerConsumer: poison message error, ignoring: {}", e.getMessage(), e);
        }
    }

    private void pauseContainers() {
        registry.getListenerContainers().forEach(container -> {
            if (container.isRunning()) {
                container.pause();
            }
        });
    }
}

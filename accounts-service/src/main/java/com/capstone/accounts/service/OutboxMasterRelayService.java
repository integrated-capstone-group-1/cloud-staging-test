package com.capstone.accounts.service;

import com.capstone.accounts.entity.OutboxMaster;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Polls OUTBOX_MASTER (Oracle XE 21c) for PENDING events owned by Accounts Service
 * and relays them to Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxMasterRelayService {

    private final OutboxMasterRepository outboxMasterRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void processOutbox() {
        List<OutboxMaster> pending = outboxMasterRepository
                .findBySourceServiceAndStatusOrderByCreatedAtAsc("accounts-service", "PENDING");

        for (OutboxMaster entry : pending) {
            try {
                String topic = resolveTopic(entry.getEventType());
                Object payloadToSend = deserializePayload(entry.getEventType(), entry.getPayload(), entry);
                kafkaTemplate.send(topic, entry.getAggregateId(), payloadToSend);

                entry.setStatus("PUBLISHED");
                entry.setPublishedAt(LocalDateTime.now());
                outboxMasterRepository.save(entry);

                log.info("OutboxMasterRelay: published event={} aggregateId={} to topic={}",
                        entry.getEventType(), entry.getAggregateId(), topic);
            } catch (Exception ex) {
                log.error("OutboxMasterRelay: failed to publish outboxId={} event={}: {}",
                        entry.getOutboxId(), entry.getEventType(), ex.getMessage());
            }
        }
    }

    private Object deserializePayload(String eventType, String payload, OutboxMaster entry) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            return switch (eventType) {
                case KafkaTopics.ACCOUNT_CREATED, "ACCOUNT_CREATED" ->
                        objectMapper.readValue(payload, com.capstone.common.dto.AccountDTO.class);
                case KafkaTopics.BALANCE_UPDATED, "BALANCE_UPDATED" ->
                        objectMapper.readValue(payload, com.capstone.common.event.BalanceUpdatedEvent.class);
                case KafkaTopics.CROSSCURRENCY_SETTLEMENT_COMPLETED, "CROSSCURRENCY_SETTLEMENT_COMPLETED" ->
                        objectMapper.readValue(payload, com.capstone.common.event.CrossCurrencySettlementCompletedEvent.class);
                case "SETTLEMENT_COMPLETED", "payment.completed", KafkaTopics.TRANSACTION_EVENTS ->
                        buildPaymentCompletedWrapper(payload, entry);
                default -> payload;
            };
        } catch (Exception ignored) {
            return payload;
        }
    }

    private Object buildPaymentCompletedWrapper(String payload, OutboxMaster entry) {
        try {
            if (payload.contains("\"payment.completed\"") || payload.contains("\"event_type\"")) {
                return objectMapper.readValue(payload, Object.class);
            }
            CoreSettlementRequest req = objectMapper.readValue(payload, CoreSettlementRequest.class);
            List<LegDTO> legs = new ArrayList<>();
            int seq = 1;
            if (req.legs() != null) {
                for (SettlementLegDto leg : req.legs()) {
                    int legNo = leg.legNo() != null ? leg.legNo() : seq++;
                    legs.add(new LegDTO(legNo, leg.accountId(), leg.direction(), leg.amount(), leg.currency()));
                }
            }
            PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                    req.postingReferenceId(),
                    entry != null && entry.getAggregateId() != null ? entry.getAggregateId() : req.postingReferenceId(),
                    "CUSTOMER",
                    legs
            );
            return new KafkaEventWrapper<>(
                    entry != null && entry.getOutboxId() != null ? entry.getOutboxId() : UUID.randomUUID().toString(),
                    "TRANSACTION",
                    entry != null && entry.getAggregateId() != null ? entry.getAggregateId() : req.postingReferenceId(),
                    "payment.completed",
                    Instant.now(),
                    completedEvent
            );
        } catch (Exception ex) {
            log.warn("OutboxMasterRelay: unable to parse settlement payload as CoreSettlementRequest, forwarding raw: {}", ex.getMessage());
            return payload;
        }
    }

    private String resolveTopic(String eventType) {
        return switch (eventType) {
            case KafkaTopics.ACCOUNT_CREATED, "ACCOUNT_CREATED" -> KafkaTopics.ACCOUNT_CREATED;
            case KafkaTopics.BALANCE_UPDATED, "BALANCE_UPDATED" -> KafkaTopics.BALANCE_UPDATED;
            case KafkaTopics.CROSSCURRENCY_SETTLEMENT_COMPLETED, "CROSSCURRENCY_SETTLEMENT_COMPLETED" ->
                    KafkaTopics.CROSSCURRENCY_SETTLEMENT_COMPLETED;
            case "SETTLEMENT_COMPLETED", "payment.completed", KafkaTopics.TRANSACTION_EVENTS ->
                    KafkaTopics.TRANSACTION_EVENTS;
            default -> eventType;
        };
    }
}

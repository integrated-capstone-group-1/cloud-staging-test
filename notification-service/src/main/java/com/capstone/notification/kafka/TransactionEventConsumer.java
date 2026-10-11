package com.capstone.notification.kafka;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.common.event.TransactionCompletedEvent;
import com.capstone.common.event.TransactionCreatedEvent;
import com.capstone.common.event.TransactionFailedEvent;
import com.capstone.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single listener on `transaction-events` that fans an incoming payload out
 * to the right handler based on its shape, since TransactionCreatedEvent,
 * TransactionCompletedEvent, TransactionFailedEvent and PaymentCompletedEvent are all published to
 * the same topic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.TRANSACTION_EVENTS, containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(Object payload) {
        if (payload == null) {
            log.warn("Received null payload on transaction-events topic; skipping");
            return;
        }
        Map<?, ?> asMap = objectMapper.convertValue(payload, LinkedHashMap.class);
        if (asMap == null) {
            log.warn("Payload could not be converted to a map: {}; skipping", payload);
            return;
        }
        try {
            String eventType = asMap.containsKey("event_type") ? String.valueOf(asMap.get("event_type"))
                    : (asMap.containsKey("eventType") ? String.valueOf(asMap.get("eventType")) : null);

            if ("payment.completed".equalsIgnoreCase(eventType) && asMap.containsKey("payload")) {
                PaymentCompletedEvent pce = objectMapper.convertValue(asMap.get("payload"), PaymentCompletedEvent.class);
                notificationService.notifySettlementCompleted(pce);
            } else if (asMap.containsKey("reason")) {
                notificationService.notifyFailed(objectMapper.convertValue(asMap, TransactionFailedEvent.class));
            } else if (asMap.containsKey("balanceAfter")) {
                notificationService.notifyCompleted(objectMapper.convertValue(asMap, TransactionCompletedEvent.class));
            } else {
                notificationService.notifyCreated(objectMapper.convertValue(asMap, TransactionCreatedEvent.class));
            }
        } catch (IllegalArgumentException e) {
            log.error("Failed to deserialize transaction event payload: {}", payload, e);
        }
    }
}

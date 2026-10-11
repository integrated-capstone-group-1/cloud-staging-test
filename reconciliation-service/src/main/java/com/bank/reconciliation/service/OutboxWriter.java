package com.bank.reconciliation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OutboxWriter(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes reconciliation events directly to Kafka topics.
     * aggregateId is used as the partition key.
     */
    public void enqueue(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            kafkaTemplate.send(eventType, aggregateId, payload);
            log.info("Published recon event {} with key {}", eventType, aggregateId);
        } catch (Exception e) {
            log.error("Failed to publish recon event {} with key {}", eventType, aggregateId, e);
        }
    }
}

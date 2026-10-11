package com.capstone.risk.kafka;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.RiskAlertEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event producer for publishing high-risk alerts to Kafka topic 'risk.alert' (DIG-79).
 * Maintains a thread-safe circular in-memory buffer for diagnostics and admin operations.
 */
@Component
@Slf4j
public class RiskAlertProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final List<RiskAlertEvent> recentAlerts = new CopyOnWriteArrayList<>();

    @Autowired
    public RiskAlertProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes a risk alert event asynchronously to Kafka.
     * Guaranteed non-blocking to preserve the <= 50ms engine SLA.
     */
    public void publishAlert(RiskAlertEvent event) {
        // Record in local buffer
        recentAlerts.add(event);
        if (recentAlerts.size() > 100) {
            recentAlerts.remove(0);
        }

        if (kafkaTemplate == null) {
            log.warn("[RISK ALERT] KafkaTemplate not available; alert buffered locally only: id={}", event.alertId());
            return;
        }

        String key = event.customerId() != null ? event.customerId() : event.alertId();
        try {
            kafkaTemplate.send(KafkaTopics.RISK_ALERT, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[RISK ALERT] Failed to publish alert id={} for customer={} to topic {}: {}",
                                    event.alertId(), event.customerId(), KafkaTopics.RISK_ALERT, ex.getMessage());
                        } else {
                            log.info("[RISK ALERT] Emitted alert id={} to topic {} partition={} offset={}",
                                    event.alertId(),
                                    KafkaTopics.RISK_ALERT,
                                    result.getRecordMetadata() != null ? result.getRecordMetadata().partition() : -1,
                                    result.getRecordMetadata() != null ? result.getRecordMetadata().offset() : -1);
                        }
                    });
        } catch (Exception ex) {
            log.error("[RISK ALERT] Exception while dispatching alert id={}: {}", event.alertId(), ex.getMessage());
        }
    }

    public List<RiskAlertEvent> getRecentAlerts() {
        return List.copyOf(recentAlerts);
    }

    public void clearRecentAlerts() {
        recentAlerts.clear();
    }
}

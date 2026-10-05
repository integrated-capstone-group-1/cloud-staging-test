package com.capstone.risk.rules;

import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskEvaluationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity Rule (V in {0.0, 1.0}):
 * Evaluates whether a customer has executed > 3 transfers in the last 5 minutes.
 * Weight in score calculation: 0.40.
 */
@Component
@Slf4j
public class VelocityRule {

    public static final String RULE_NAME = "VELOCITY_LIMIT_EXCEEDED";

    private final RiskEngineProperties properties;
    private final ConcurrentHashMap<String, Deque<Instant>> customerTransferTimestamps = new ConcurrentHashMap<>();

    public VelocityRule(RiskEngineProperties properties) {
        this.properties = properties;
    }

    public String getRuleName() {
        return RULE_NAME;
    }

    public double getWeight() {
        return properties.getRules().getWeights().getVelocity();
    }

    /**
     * Evaluates if the customer has breached the velocity threshold (> 3 transfers in 5 minutes).
     *
     * @param request the risk evaluation request
     * @return true if velocity threshold is exceeded (> 3 transfers in 5 mins), false otherwise
     */
    public boolean evaluate(RiskEvaluationRequest request) {
        if (request == null || request.getCustomerId() == null || request.getCustomerId().isBlank()) {
            return false;
        }

        String customerId = request.getCustomerId();
        Deque<Instant> timestamps = customerTransferTimestamps.get(customerId);
        if (timestamps == null || timestamps.isEmpty()) {
            return false;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getRules().getVelocity().getWindowMinutes()));
        long recentCount;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            recentCount = timestamps.size();
        }

        boolean exceeded = recentCount > properties.getRules().getVelocity().getMaxTransfers();
        if (exceeded) {
            log.warn("Velocity limit exceeded for customer {}: {} transfers in last {} minutes (threshold: {})",
                    customerId, recentCount, properties.getRules().getVelocity().getWindowMinutes(),
                    properties.getRules().getVelocity().getMaxTransfers());
        }
        return exceeded;
    }

    /**
     * Records a transfer timestamp for a customer.
     */
    public void recordTransfer(String customerId) {
        recordTransfer(customerId, Instant.now());
    }

    public void recordTransfer(String customerId, Instant timestamp) {
        if (customerId == null || customerId.isBlank() || timestamp == null) {
            return;
        }
        customerTransferTimestamps.compute(customerId, (k, existing) -> {
            Deque<Instant> deque = existing != null ? existing : new ArrayDeque<>();
            synchronized (deque) {
                deque.addLast(timestamp);
            }
            return deque;
        });
    }

    public void reset() {
        customerTransferTimestamps.clear();
    }

    public void reset(String customerId) {
        if (customerId != null) {
            customerTransferTimestamps.remove(customerId);
        }
    }
}

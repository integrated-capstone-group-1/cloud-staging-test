package com.capstone.risk.rules;

import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskEvaluationRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Device Anomaly Rule (D in {0.0, 1.0}):
 * Evaluates whether transaction originates from a new or unverified device.
 * Weight in score calculation: 0.25.
 */
@Component
@Slf4j
public class DeviceAnomalyRule {

    public static final String RULE_NAME = "DEVICE_ANOMALY";

    private final RiskEngineProperties properties;
    private final ConcurrentHashMap<String, Set<String>> trustedDevices = new ConcurrentHashMap<>();

    public DeviceAnomalyRule(RiskEngineProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // Pre-seed known customer device from CONTRACTS.md baseline
        trustDevice("cust-user-001", "dev-fingerprint-99");
    }

    public String getRuleName() {
        return RULE_NAME;
    }

    public double getWeight() {
        return properties.getRules().getWeights().getDevice();
    }

    /**
     * Evaluates whether the device is new, unverified, or untrusted.
     *
     * @param request the risk evaluation request
     * @return true if device is anomalous/unverified (D = 1.0), false if recognized/verified (D = 0.0)
     */
    public boolean evaluate(RiskEvaluationRequest request) {
        if (request == null) {
            return true;
        }

        String deviceId = request.getDeviceId();
        String customerId = request.getCustomerId();

        // 1. Missing or blank device ID is unverified
        if (deviceId == null || deviceId.isBlank()) {
            log.warn("Device anomaly triggered: missing device ID for customer {}", customerId);
            return true;
        }

        // 2. Explicit unverified/unknown pattern in device identifier
        String lower = deviceId.toLowerCase();
        if (lower.contains("unverified") || lower.contains("unknown") || lower.contains("untrusted")) {
            log.warn("Device anomaly triggered: unverified device pattern '{}' for customer {}", deviceId, customerId);
            return true;
        }

        // 3. Known device check for customer
        if (customerId != null && !customerId.isBlank()) {
            Set<String> customerKnown = trustedDevices.get(customerId);
            if (customerKnown != null && customerKnown.contains(deviceId)) {
                return false; // Trusted device
            }
        }

        // 4. Strict trust check: if device is not pre-registered for this customer, it is a new device
        if (properties.getRules().getDevice().isStrictTrustCheck()) {
            log.warn("Device anomaly triggered: new/unregistered device '{}' for customer {}", deviceId, customerId);
            return true;
        }

        return false;
    }

    public void trustDevice(String customerId, String deviceId) {
        if (customerId != null && deviceId != null) {
            trustedDevices.computeIfAbsent(customerId, k -> ConcurrentHashMap.newKeySet()).add(deviceId);
        }
    }

    public void untrustDevice(String customerId, String deviceId) {
        if (customerId != null && deviceId != null) {
            Set<String> set = trustedDevices.get(customerId);
            if (set != null) {
                set.remove(deviceId);
            }
        }
    }

    public boolean isDeviceTrusted(String customerId, String deviceId) {
        Set<String> set = trustedDevices.get(customerId);
        return set != null && set.contains(deviceId);
    }

    public void reset() {
        trustedDevices.clear();
        trustDevice("cust-user-001", "dev-fingerprint-99");
    }
}

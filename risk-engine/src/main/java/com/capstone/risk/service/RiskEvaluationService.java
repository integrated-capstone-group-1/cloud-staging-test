package com.capstone.risk.service;

import com.capstone.common.event.RiskAlertEvent;
import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.kafka.RiskAlertProducer;
import com.capstone.risk.model.RiskDecision;
import com.capstone.risk.model.RiskLevel;
import com.capstone.risk.rules.AmountAnomalyRule;
import com.capstone.risk.rules.DeviceAnomalyRule;
import com.capstone.risk.rules.FxRoundTripAbuseRule;
import com.capstone.risk.rules.VelocityRule;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * High-performance Java Synchronous Risk & Fraud Screening Engine (BR-08, BR-18, TR-R01, TR-R04).
 * Evaluates Velocity (V), Amount Anomaly (A), Device Anomaly (D), and FX Round-Trip Abuse heuristics to calculate
 * normalized weighted risk score S in [0.00, 1.00] and maps to calibrated decisions:
 * - S < 0.50: LOW -> ALLOW
 * - 0.50 <= S <= 0.85: MEDIUM -> STEP_UP_OTP
 * - S > 0.85: HIGH -> HOLD
 */
@Service
@Slf4j
public class RiskEvaluationService {

    private final VelocityRule velocityRule;
    private final AmountAnomalyRule amountAnomalyRule;
    private final DeviceAnomalyRule deviceAnomalyRule;
    private final FxRoundTripAbuseRule fxRoundTripAbuseRule;
    private final RiskEngineProperties properties;
    private final RiskAlertProducer riskAlertProducer;

    public RiskEvaluationService(
            VelocityRule velocityRule,
            AmountAnomalyRule amountAnomalyRule,
            DeviceAnomalyRule deviceAnomalyRule,
            FxRoundTripAbuseRule fxRoundTripAbuseRule,
            RiskEngineProperties properties) {
        this(velocityRule, amountAnomalyRule, deviceAnomalyRule, fxRoundTripAbuseRule, properties, null);
    }

    @Autowired
    public RiskEvaluationService(
            VelocityRule velocityRule,
            AmountAnomalyRule amountAnomalyRule,
            DeviceAnomalyRule deviceAnomalyRule,
            FxRoundTripAbuseRule fxRoundTripAbuseRule,
            RiskEngineProperties properties,
            @Autowired(required = false) RiskAlertProducer riskAlertProducer) {
        this.velocityRule = velocityRule;
        this.amountAnomalyRule = amountAnomalyRule;
        this.deviceAnomalyRule = deviceAnomalyRule;
        this.fxRoundTripAbuseRule = fxRoundTripAbuseRule;
        this.properties = properties;
        this.riskAlertProducer = riskAlertProducer;
    }

    /**
     * Evaluates transaction risk synchronously and records the transfer into velocity tracker.
     *
     * @param request transaction details for evaluation
     * @return risk evaluation response containing score, level, decision, triggered rules, and latency
     */
    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        return evaluate(request, true);
    }

    /**
     * Evaluates transaction risk synchronously with optional velocity recording.
     *
     * @param request transaction details for evaluation
     * @param recordVelocity whether to record this transaction in velocity tracking
     * @return risk evaluation response
     */
    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request, boolean recordVelocity) {
        long startNano = System.nanoTime();

        List<String> triggeredRules = new ArrayList<>();

        // 1. Evaluate Velocity Rule (V in {0.0, 1.0}, weight 0.40)
        boolean velocityTriggered = velocityRule.evaluate(request);
        double v = velocityTriggered ? 1.0 : 0.0;
        if (velocityTriggered) {
            triggeredRules.add(velocityRule.getRuleName());
        }

        // 2. Evaluate Amount Anomaly Rule (A in {0.0, 1.0}, weight 0.35)
        boolean amountTriggered = amountAnomalyRule.evaluate(request);
        double a = amountTriggered ? 1.0 : 0.0;
        if (amountTriggered) {
            triggeredRules.add(amountAnomalyRule.getRuleName());
        }

        // 3. Evaluate Device Anomaly Rule (D in {0.0, 1.0}, weight 0.25)
        boolean deviceTriggered = deviceAnomalyRule.evaluate(request);
        double d = deviceTriggered ? 1.0 : 0.0;
        if (deviceTriggered) {
            triggeredRules.add(deviceAnomalyRule.getRuleName());
        }

        // 4. Evaluate FX Round-Trip Abuse Rule (DIG-22, weight 0.35)
        boolean fxTriggered = false;
        if (request.getTargetCurrency() != null && !request.getTargetCurrency().isBlank()) {
            fxTriggered = fxRoundTripAbuseRule.evaluate(
                    request.getCustomerId(),
                    request.getCurrency(),
                    request.getTargetCurrency()
            );
            if (fxTriggered) {
                triggeredRules.add(fxRoundTripAbuseRule.getRuleName());
            }
        }
        double fx = fxTriggered ? 1.0 : 0.0;

        // 5. Calculate normalized weighted score: S = min(1.00, wV*V + wA*A + wD*D + wFx*FX)
        double wV = properties.getRules().getWeights().getVelocity();
        double wA = properties.getRules().getWeights().getAmount();
        double wD = properties.getRules().getWeights().getDevice();
        double wFx = properties.getRules().getWeights().getFxRoundTrip();

        double rawScore = (wV * v) + (wA * a) + (wD * d) + (wFx * fx);
        double score = Math.min(1.00, rawScore);

        // Normalize to 2 decimal places
        BigDecimal scoreBd = BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
        score = scoreBd.doubleValue();

        // 6. Calibrated Decision Mapping (ADR-001)
        double lowMax = properties.getThresholds().getLowMax();       // 0.50
        double mediumMax = properties.getThresholds().getMediumMax(); // 0.85

        RiskLevel level;
        RiskDecision decision;

        if (score < lowMax) {
            level = RiskLevel.LOW;
            decision = RiskDecision.ALLOW;
        } else if (score <= mediumMax) {
            level = RiskLevel.MEDIUM;
            decision = RiskDecision.STEP_UP_OTP;
        } else {
            level = RiskLevel.HIGH;
            decision = RiskDecision.HOLD;
        }

        // 7. Record transfer in velocity / FX tracker if requested
        if (recordVelocity && request.getCustomerId() != null) {
            velocityRule.recordTransfer(request.getCustomerId());
            if (request.getTargetCurrency() != null && !request.getTargetCurrency().isBlank()) {
                fxRoundTripAbuseRule.recordConversion(
                        request.getCustomerId(),
                        request.getCurrency(),
                        request.getTargetCurrency()
                );
            }
        }

        // 8. Publish Kafka alert on high-risk hold decision (DIG-79)
        if ((decision == RiskDecision.HOLD || score > mediumMax) && riskAlertProducer != null) {
            String alertReason = String.format("Risk score %.2f exceeded threshold %.2f (rules: %s)",
                    score, mediumMax, triggeredRules);
            RiskAlertEvent alertEvent = new RiskAlertEvent(
                    UUID.randomUUID().toString(),
                    request.getTransactionId(),
                    request.getCustomerId(),
                    request.getAmount(),
                    request.getCurrency(),
                    score,
                    level.name(),
                    decision.name(),
                    List.copyOf(triggeredRules),
                    Instant.now(),
                    alertReason
            );
            riskAlertProducer.publishAlert(alertEvent);
        }

        long elapsedNano = System.nanoTime() - startNano;
        double latencyMs = BigDecimal.valueOf(elapsedNano / 1_000_000.0)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

        // Structured MDC logging for Loki
        MDC.put("transactionId", request.getTransactionId() != null ? request.getTransactionId() : "");
        MDC.put("customerId", request.getCustomerId() != null ? request.getCustomerId() : "");
        MDC.put("riskScore", String.valueOf(score));
        MDC.put("riskLevel", level.name());
        MDC.put("riskDecision", decision.name());
        MDC.put("component", "risk-engine");
        MDC.put("event_type", "RISK_EVALUATED");

        log.info("Risk screening result for txn {}: score={}, level={}, decision={}, triggeredRules={}, latency={}ms",
                request.getTransactionId(), score, level, decision, triggeredRules, latencyMs);

        MDC.clear();

        return RiskEvaluationResponse.builder()
                .score(score)
                .level(level.name())
                .decision(decision.name())
                .triggeredRules(triggeredRules)
                .evaluationLatencyMs(latencyMs)
                .build();
    }
}

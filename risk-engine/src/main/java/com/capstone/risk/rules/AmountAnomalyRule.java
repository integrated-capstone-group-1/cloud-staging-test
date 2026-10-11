package com.capstone.risk.rules;

import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskEvaluationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Amount Anomaly Rule (A in {0.0, 1.0}):
 * Evaluates whether transaction amount > 100,000 PHP or falls within AMLA structuring threshold.
 * Weight in score calculation: 0.35.
 */
@Component
@Slf4j
public class AmountAnomalyRule {

    public static final String RULE_NAME = "AMOUNT_ANOMALY";

    private final RiskEngineProperties properties;

    public AmountAnomalyRule(RiskEngineProperties properties) {
        this.properties = properties;
    }

    public String getRuleName() {
        return RULE_NAME;
    }

    public double getWeight() {
        return properties.getRules().getWeights().getAmount();
    }

    /**
     * Evaluates if transaction amount breaches anomaly thresholds (> 100,000 PHP or AMLA structuring band).
     *
     * @param request the risk evaluation request
     * @return true if amount anomaly is triggered (A = 1.0), false otherwise (A = 0.0)
     */
    public boolean evaluate(RiskEvaluationRequest request) {
        if (request == null || request.getAmount() == null) {
            return false;
        }

        BigDecimal amount = request.getAmount();
        BigDecimal phpThreshold = properties.getRules().getAmount().getPhpThreshold();
        BigDecimal amlaLower = properties.getRules().getAmount().getAmlaStructuringLower();
        BigDecimal amlaUpper = properties.getRules().getAmount().getAmlaStructuringUpper();

        // 1. Check if amount exceeds 100,000 PHP (or foreign currency equivalent / default)
        boolean exceedsThreshold = amount.compareTo(phpThreshold) > 0;

        // 2. Check if amount is just under AMLA threshold (structuring / smurfing band: 400,000 <= amount <= 500,000)
        boolean isStructuring = amount.compareTo(amlaLower) >= 0 && amount.compareTo(amlaUpper) <= 0;

        boolean triggered = exceedsThreshold || isStructuring;
        if (triggered) {
            log.warn("Amount anomaly rule triggered for amount {} {}: exceedsThreshold={}, isStructuring={}",
                    amount, request.getCurrency(), exceedsThreshold, isStructuring);
        }
        return triggered;
    }
}

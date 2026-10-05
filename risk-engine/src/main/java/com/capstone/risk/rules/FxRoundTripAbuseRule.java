package com.capstone.risk.rules;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FX Round-Trip Abuse Rule (DIG-22):
 * Detects rapid round-trip currency conversions (e.g., PHP -> USD -> PHP within short window)
 * to prevent speculative FX arbitrage and rate exploitation.
 */
@Component
@Slf4j
public class FxRoundTripAbuseRule {

    public static final String RULE_NAME = "FX_ROUND_TRIP_ABUSE";

    public record FxConversionRecord(String sourceCurrency, String targetCurrency, Instant timestamp) {}

    private final ConcurrentHashMap<String, Deque<FxConversionRecord>> history = new ConcurrentHashMap<>();
    private final Duration window = Duration.ofMinutes(5);

    public String getRuleName() {
        return RULE_NAME;
    }

    /**
     * Evaluates whether a new conversion from sourceCurrency to targetCurrency constitutes
     * an inverted round-trip flip of a recent conversion.
     */
    public boolean evaluate(String customerId, String sourceCurrency, String targetCurrency) {
        if (customerId == null || sourceCurrency == null || targetCurrency == null) {
            return false;
        }

        Deque<FxConversionRecord> records = history.get(customerId);
        if (records == null || records.isEmpty()) {
            return false;
        }

        Instant cutoff = Instant.now().minus(window);
        synchronized (records) {
            while (!records.isEmpty() && records.peekFirst().timestamp().isBefore(cutoff)) {
                records.pollFirst();
            }

            for (FxConversionRecord rec : records) {
                // If previously converted A -> B, and now converting B -> A within window
                if (rec.sourceCurrency().equalsIgnoreCase(targetCurrency)
                        && rec.targetCurrency().equalsIgnoreCase(sourceCurrency)) {
                    log.warn("FX round-trip abuse detected for customer {}: flipped {} -> {} after {} -> {} in < {} mins",
                            customerId, sourceCurrency, targetCurrency,
                            rec.sourceCurrency(), rec.targetCurrency(), window.toMinutes());
                    return true;
                }
            }
        }
        return false;
    }

    public void recordConversion(String customerId, String sourceCurrency, String targetCurrency) {
        if (customerId == null || sourceCurrency == null || targetCurrency == null) {
            return;
        }
        history.compute(customerId, (k, existing) -> {
            Deque<FxConversionRecord> deque = existing != null ? existing : new ArrayDeque<>();
            synchronized (deque) {
                deque.addLast(new FxConversionRecord(sourceCurrency, targetCurrency, Instant.now()));
            }
            return deque;
        });
    }

    public void reset() {
        history.clear();
    }
}

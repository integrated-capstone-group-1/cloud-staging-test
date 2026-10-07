package com.capstone.risk.service;

import com.capstone.common.event.RiskAlertEvent;
import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.kafka.RiskAlertProducer;
import com.capstone.risk.rules.AmountAnomalyRule;
import com.capstone.risk.rules.DeviceAnomalyRule;
import com.capstone.risk.rules.FxRoundTripAbuseRule;
import com.capstone.risk.rules.VelocityRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Done-When Verification Suite for Task T-003:
 * mvn test -pl risk-engine -Dtest=RiskEvaluationServiceTest
 *
 * Verifies:
 * 1. Low-risk transaction (V=0, A=0, D=0) returns S = 0.00 (ALLOW).
 * 2. High-velocity alone (V=1, A=0, D=0) returns S = 0.40 (ALLOW).
 * 3. Velocity + Amount structuring (V=1, A=1, D=0) returns S = 0.75 (STEP_UP_OTP).
 * 4. Tripled anomaly (V=1, A=1, D=1) returns S = 1.00 > 0.85 (HOLD).
 * 5. Average execution latency is under 50ms.
 * 6. DIG-22: Changing weight changes scoring without redeploy.
 * 7. DIG-22: FX round-trip pattern raises the score.
 */
class RiskEvaluationServiceTest {

    private VelocityRule velocityRule;
    private AmountAnomalyRule amountAnomalyRule;
    private DeviceAnomalyRule deviceAnomalyRule;
    private FxRoundTripAbuseRule fxRoundTripAbuseRule;
    private RiskEngineProperties properties;
    private RiskEvaluationService riskService;

    @BeforeEach
    void setUp() {
        properties = new RiskEngineProperties();
        velocityRule = new VelocityRule(properties);
        amountAnomalyRule = new AmountAnomalyRule(properties);
        deviceAnomalyRule = new DeviceAnomalyRule(properties);
        deviceAnomalyRule.init();
        fxRoundTripAbuseRule = new FxRoundTripAbuseRule();

        riskService = new RiskEvaluationService(
                velocityRule,
                amountAnomalyRule,
                deviceAnomalyRule,
                fxRoundTripAbuseRule,
                properties
        );
    }

    @Test
    @DisplayName("1. Low-risk transaction (V=0, A=0, D=0) returns S = 0.00 (ALLOW)")
    void test1_lowRiskTransaction_returnsZeroScoreAndAllow() {
        String customerId = "cust-user-001";
        String deviceId = "dev-fingerprint-99"; // pre-seeded as trusted
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-001-uuid")
                .customerId(customerId)
                .amount(new BigDecimal("2517.2000"))
                .currency("PHP")
                .ipAddress("192.168.1.50")
                .deviceId(deviceId)
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.00, response.getScore(), 0.001, "Low-risk score must be 0.00");
        assertEquals("LOW", response.getLevel(), "Risk level must be LOW");
        assertEquals("ALLOW", response.getDecision(), "Risk decision must be ALLOW");
        assertTrue(response.getTriggeredRules().isEmpty(), "No rules should trigger for low-risk transaction");
    }

    @Test
    @DisplayName("2. High-velocity alone (V=1, A=0, D=0) returns S = 0.40 (ALLOW)")
    void test2_highVelocityAlone_returnsScore040AndAllow() {
        String customerId = "cust-velocity-user";
        String deviceId = "dev-trusted-01";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        // Pre-record 4 transfers to exceed velocity threshold (> 3 transfers in 5 minutes)
        for (int i = 0; i < 4; i++) {
            velocityRule.recordTransfer(customerId);
        }

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-002-uuid")
                .customerId(customerId)
                .amount(new BigDecimal("500.0000")) // Well below 100,000 PHP (A=0)
                .currency("PHP")
                .ipAddress("192.168.1.50")
                .deviceId(deviceId) // Recognized device (D=0)
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.40, response.getScore(), 0.001, "High-velocity alone score must be 0.40");
        assertEquals("LOW", response.getLevel(), "Score 0.40 < 0.50 must map to LOW");
        assertEquals("ALLOW", response.getDecision(), "Score 0.40 < 0.50 must map to ALLOW");
        assertEquals(1, response.getTriggeredRules().size(), "Only velocity rule should trigger");
        assertTrue(response.getTriggeredRules().contains(VelocityRule.RULE_NAME));
    }

    @Test
    @DisplayName("3. Velocity + Amount structuring (V=1, A=1, D=0) returns S = 0.75 (STEP_UP_OTP)")
    void test3_velocityAndAmountStructuring_returnsScore075AndStepUpOtp() {
        String customerId = "cust-structuring-user";
        String deviceId = "dev-trusted-02";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        // Pre-record 4 transfers for V = 1.0
        for (int i = 0; i < 4; i++) {
            velocityRule.recordTransfer(customerId);
        }

        // Amount of 450,000 PHP falls in AMLA structuring band (400,000 to 500,000 PHP) and > 100,000 PHP (A = 1.0)
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-003-uuid")
                .customerId(customerId)
                .amount(new BigDecimal("450000.0000"))
                .currency("PHP")
                .ipAddress("192.168.1.50")
                .deviceId(deviceId) // Trusted device (D=0)
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.75, response.getScore(), 0.001, "Velocity (0.40) + Amount (0.35) must equal 0.75");
        assertEquals("MEDIUM", response.getLevel(), "Score 0.75 must map to MEDIUM (0.50 <= S <= 0.85)");
        assertEquals("STEP_UP_OTP", response.getDecision(), "Score 0.75 must require STEP_UP_OTP");
        assertEquals(2, response.getTriggeredRules().size(), "Velocity and amount rules must trigger");
        assertTrue(response.getTriggeredRules().contains(VelocityRule.RULE_NAME));
        assertTrue(response.getTriggeredRules().contains(AmountAnomalyRule.RULE_NAME));
        assertFalse(response.getTriggeredRules().contains(DeviceAnomalyRule.RULE_NAME));
    }

    @Test
    @DisplayName("4. Tripled anomaly (V=1, A=1, D=1) returns S = 1.00 > 0.85 (HOLD)")
    void test4_tripledAnomaly_returnsScore100AndHold() {
        String customerId = "cust-fraud-user";
        String unverifiedDevice = "dev-unverified-unknown-01"; // Unverified device (D = 1.0)

        // Pre-record 4 transfers for V = 1.0
        for (int i = 0; i < 4; i++) {
            velocityRule.recordTransfer(customerId);
        }

        // Amount > 100,000 PHP for A = 1.0
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-004-uuid")
                .customerId(customerId)
                .amount(new BigDecimal("150000.0000"))
                .currency("PHP")
                .ipAddress("10.0.0.99")
                .deviceId(unverifiedDevice)
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(1.00, response.getScore(), 0.001, "Tripled anomaly must cap at 1.00");
        assertTrue(response.getScore() > 0.85, "Tripled anomaly score 1.00 must exceed 0.85");
        assertEquals("HIGH", response.getLevel(), "Score > 0.85 must map to HIGH");
        assertEquals("HOLD", response.getDecision(), "Score > 0.85 must map to HOLD");
        assertEquals(3, response.getTriggeredRules().size(), "All three rules must trigger");
        assertTrue(response.getTriggeredRules().contains(VelocityRule.RULE_NAME));
        assertTrue(response.getTriggeredRules().contains(AmountAnomalyRule.RULE_NAME));
        assertTrue(response.getTriggeredRules().contains(DeviceAnomalyRule.RULE_NAME));
    }

    @Test
    @DisplayName("5. Average execution latency is under 50ms")
    void test5_averageExecutionLatencyUnder50ms() {
        String customerId = "cust-perf-benchmark";
        String deviceId = "dev-trusted-perf";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-perf-benchmark")
                .customerId(customerId)
                .amount(new BigDecimal("12000.0000"))
                .currency("PHP")
                .ipAddress("127.0.0.1")
                .deviceId(deviceId)
                .build();

        // Warm-up JIT compiler
        for (int i = 0; i < 200; i++) {
            riskService.evaluate(request, false);
        }

        // Measure benchmark over 1,000 iterations
        int iterations = 1000;
        long totalNano = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            RiskEvaluationResponse resp = riskService.evaluate(request, false);
            totalNano += (System.nanoTime() - start);

            assertTrue(resp.getEvaluationLatencyMs() >= 0, "Evaluation latency must be non-negative");
        }

        double avgLatencyMs = (totalNano / (double) iterations) / 1_000_000.0;
        assertTrue(avgLatencyMs < 50.0,
                String.format("Average execution latency must be < 50ms, was %.4f ms", avgLatencyMs));
    }

    @Test
    @DisplayName("Amount Anomaly alone (V=0, A=1, D=0) returns S = 0.35 (ALLOW)")
    void testAmountAnomalyAlone_returnsScore035AndAllow() {
        String customerId = "cust-amount-alone";
        String deviceId = "dev-trusted-alone";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-amt-alone")
                .customerId(customerId)
                .amount(new BigDecimal("120000.0000")) // > 100,000 PHP (A=1)
                .currency("PHP")
                .deviceId(deviceId) // D=0
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.35, response.getScore(), 0.001);
        assertEquals("LOW", response.getLevel());
        assertEquals("ALLOW", response.getDecision());
        assertEquals(1, response.getTriggeredRules().size());
        assertTrue(response.getTriggeredRules().contains(AmountAnomalyRule.RULE_NAME));
    }

    @Test
    @DisplayName("Device Anomaly alone (V=0, A=0, D=1) returns S = 0.25 (ALLOW)")
    void testDeviceAnomalyAlone_returnsScore025AndAllow() {
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-dev-alone")
                .customerId("cust-new-user")
                .amount(new BigDecimal("500.0000")) // A=0
                .currency("PHP")
                .deviceId("unverified-phone-model-x") // D=1
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.25, response.getScore(), 0.001);
        assertEquals("LOW", response.getLevel());
        assertEquals("ALLOW", response.getDecision());
        assertEquals(1, response.getTriggeredRules().size());
        assertTrue(response.getTriggeredRules().contains(DeviceAnomalyRule.RULE_NAME));
    }

    @Test
    @DisplayName("Device + Amount Anomaly (V=0, A=1, D=1) returns S = 0.60 (STEP_UP_OTP)")
    void testDeviceAndAmountAnomaly_returnsScore060AndStepUpOtp() {
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-dev-amt")
                .customerId("cust-user-both")
                .amount(new BigDecimal("150000.0000")) // A=1
                .currency("PHP")
                .deviceId("unknown-device-fingerprint") // D=1
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.60, response.getScore(), 0.001);
        assertEquals("MEDIUM", response.getLevel());
        assertEquals("STEP_UP_OTP", response.getDecision());
        assertEquals(2, response.getTriggeredRules().size());
        assertTrue(response.getTriggeredRules().contains(AmountAnomalyRule.RULE_NAME));
        assertTrue(response.getTriggeredRules().contains(DeviceAnomalyRule.RULE_NAME));
    }

    @Test
    @DisplayName("Velocity auto-tracking triggers on 5th consecutive transaction")
    void testVelocityAutoTrackingConsecutiveCalls() {
        String customerId = "cust-consecutive-" + UUID.randomUUID();
        String deviceId = "dev-trusted-consecutive";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-consecutive")
                .customerId(customerId)
                .amount(new BigDecimal("100.0000"))
                .currency("PHP")
                .deviceId(deviceId)
                .build();

        // 1st to 4th calls do not exceed velocity (threshold is > 3)
        for (int i = 1; i <= 4; i++) {
            RiskEvaluationResponse r = riskService.evaluate(request, true);
            assertEquals(0.00, r.getScore(), "Calls 1-4 should not breach velocity");
        }

        // 5th call has 4 prior transfers in last 5 minutes -> velocity breached
        RiskEvaluationResponse fifthResponse = riskService.evaluate(request, true);
        assertEquals(0.40, fifthResponse.getScore(), "5th call must breach velocity (V=1)");
        assertEquals("ALLOW", fifthResponse.getDecision());
        assertTrue(fifthResponse.getTriggeredRules().contains(VelocityRule.RULE_NAME));
    }

    @Test
    @DisplayName("DIG-22 AC 1: Changing a weight changes scoring without redeploy")
    void testDig22_changingWeightChangesScoringWithoutRedeploy() {
        String customerId = "cust-weight-param";
        String deviceId = "dev-trusted-param";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        // Pre-record 4 transfers for V = 1.0
        for (int i = 0; i < 4; i++) {
            velocityRule.recordTransfer(customerId);
        }

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-weight-test")
                .customerId(customerId)
                .amount(new BigDecimal("100.0000"))
                .currency("PHP")
                .deviceId(deviceId)
                .build();

        // Baseline score with default weight 0.40
        RiskEvaluationResponse baseline = riskService.evaluate(request, false);
        assertEquals(0.40, baseline.getScore(), 0.001);

        // Dynamically adjust velocity weight in property store from 0.40 to 0.60
        properties.getRules().getWeights().setVelocity(0.60);

        RiskEvaluationResponse reevaluated = riskService.evaluate(request, false);
        assertEquals(0.60, reevaluated.getScore(), 0.001, "Changing weight dynamically must adjust score without redeploy");
        assertEquals("MEDIUM", reevaluated.getLevel(), "Score 0.60 maps to MEDIUM");
        assertEquals("STEP_UP_OTP", reevaluated.getDecision(), "Score 0.60 requires STEP_UP_OTP");
    }

    @Test
    @DisplayName("DIG-22 AC 2: Round-trip FX pattern raises the score")
    void testDig22_roundTripPatternRaisesScore() {
        String customerId = "cust-fx-arbitrageur";
        String deviceId = "dev-trusted-fx";
        deviceAnomalyRule.trustDevice(customerId, deviceId);

        // First leg: customer converts PHP -> USD (recorded)
        fxRoundTripAbuseRule.recordConversion(customerId, "PHP", "USD");

        // Second leg within 5-minute window: customer inverts conversion USD -> PHP
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-fx-roundtrip")
                .customerId(customerId)
                .amount(new BigDecimal("5000.0000"))
                .currency("USD")
                .targetCurrency("PHP")
                .deviceId(deviceId)
                .build();

        RiskEvaluationResponse response = riskService.evaluate(request, false);

        assertEquals(0.35, response.getScore(), 0.001, "FX round-trip abuse weight (0.35) raises score from 0.00");
        assertEquals(1, response.getTriggeredRules().size());
        assertTrue(response.getTriggeredRules().contains(FxRoundTripAbuseRule.RULE_NAME),
                "Triggered rules must include FX_ROUND_TRIP_ABUSE");
    }

    @Test
    @DisplayName("DIG-79: High-risk HOLD decision (S > 0.85) emits Kafka risk alert event")
    void testDig79_highRiskHoldEmitsKafkaAlert() {
        RiskAlertProducer alertProducer = new RiskAlertProducer(null);
        RiskEvaluationService riskServiceWithAlerts = new RiskEvaluationService(
                velocityRule,
                amountAnomalyRule,
                deviceAnomalyRule,
                fxRoundTripAbuseRule,
                properties,
                alertProducer
        );

        String customerId = "cust-syndicate-boss";
        // Pre-seed 4 transfers to trigger Velocity (> 3 transfers => V = 1.0)
        velocityRule.recordTransfer(customerId);
        velocityRule.recordTransfer(customerId);
        velocityRule.recordTransfer(customerId);
        velocityRule.recordTransfer(customerId);

        // Tripled Anomaly: V=1 (0.40) + A=1 (0.35) + D=1 (0.25) => Score = 1.00 > 0.85
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-syndicate-heist-01")
                .customerId(customerId)
                .amount(new BigDecimal("150000.0000")) // Exceeds 100,000 threshold (A = 1.0)
                .currency("PHP")
                .ipAddress("192.168.10.99")
                .deviceId("dev-unverified-burner-phone") // Untrusted device (D = 1.0)
                .build();

        RiskEvaluationResponse response = riskServiceWithAlerts.evaluate(request, false);

        assertEquals(1.00, response.getScore(), 0.001);
        assertEquals("HIGH", response.getLevel());
        assertEquals("HOLD", response.getDecision());

        // Verify alert event was generated and buffered
        List<RiskAlertEvent> recentAlerts = alertProducer.getRecentAlerts();
        assertFalse(recentAlerts.isEmpty(), "Recent alerts must not be empty on high-risk HOLD");

        RiskAlertEvent alert = recentAlerts.get(0);
        assertEquals("txn-syndicate-heist-01", alert.transactionId());
        assertEquals(customerId, alert.customerId());
        assertEquals(1.00, alert.riskScore(), 0.001);
        assertEquals("HIGH", alert.riskLevel());
        assertEquals("HOLD", alert.riskDecision());
        assertTrue(alert.triggeredRules().contains(VelocityRule.RULE_NAME));
        assertTrue(alert.triggeredRules().contains(AmountAnomalyRule.RULE_NAME));
        assertTrue(alert.triggeredRules().contains(DeviceAnomalyRule.RULE_NAME));
    }
}

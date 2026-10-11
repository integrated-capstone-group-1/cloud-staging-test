package com.capstone.risk.client;

import com.capstone.common.exception.SecurityCheckUnavailableException;
import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.service.RiskEvaluationService;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Caller Client with Resilience4j TimeLimiter SLA enforcement (BR-08, TR-R01, DIG-23).
 * Limits synchronous risk evaluation to <= 200ms.
 * Fails closed immediately with SecurityCheckUnavailableException (HTTP 503) on breach.
 */
@Component
@Slf4j
public class RiskClient {

    private final RiskEvaluationService riskEvaluationService;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired
    public RiskClient(RiskEvaluationService riskEvaluationService) {
        this(riskEvaluationService, Duration.ofMillis(200));
    }

    public RiskClient(RiskEvaluationService riskEvaluationService, Duration timeoutDuration) {
        this.riskEvaluationService = riskEvaluationService;
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(timeoutDuration)
                .cancelRunningFuture(true)
                .build();
        this.timeLimiter = TimeLimiter.of("riskEvaluationLimiter", config);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Executes risk evaluation protected by Resilience4j TimeLimiter <= 200ms.
     * Any timeout, network failure, or downstream error triggers fail-closed security.
     *
     * @param request the risk evaluation request
     * @return evaluated risk response
     * @throws SecurityCheckUnavailableException if call exceeds 200ms SLA or fails
     */
    public RiskEvaluationResponse evaluateWithSla(RiskEvaluationRequest request) {
        Supplier<CompletableFuture<RiskEvaluationResponse>> futureSupplier = () ->
                CompletableFuture.supplyAsync(() -> riskEvaluationService.evaluate(request), executor);

        try {
            return timeLimiter.executeFutureSupplier(futureSupplier);
        } catch (TimeoutException ex) {
            log.error("Risk evaluation SLA breached (> 200ms) for txn {}. Failing closed.",
                    request.getTransactionId(), ex);
            throw new SecurityCheckUnavailableException(
                    "Risk screening exceeded 200ms SLA threshold. Security check unavailable.", ex);
        } catch (Exception ex) {
            log.error("Risk engine call failed for txn {}. Failing closed.",
                    request.getTransactionId(), ex);
            throw new SecurityCheckUnavailableException(
                    "Risk screening service unavailable. Failing closed per security invariant.", ex);
        }
    }
}

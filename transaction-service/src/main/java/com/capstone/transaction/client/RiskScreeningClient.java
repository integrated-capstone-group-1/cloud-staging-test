package com.capstone.transaction.client;

import com.capstone.common.exception.SecurityCheckUnavailableException;
import com.capstone.common.security.JwtTokenProvider;
import com.capstone.transaction.dto.risk.RiskEvaluationRequest;
import com.capstone.transaction.dto.risk.RiskEvaluationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Synchronous client to risk-engine enforcing <= 200ms fail-closed SLA.
 * DIG-23 / T-001.
 */
@Component
@Slf4j
public class RiskScreeningClient {

    private final RestClient restClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final long timeoutMs;

    public RiskScreeningClient(
            @Value("${risk.service.url:http://localhost:8088}") String riskServiceUrl,
            @Value("${risk.evaluation.timeout-ms:200}") long timeoutMs,
            JwtTokenProvider jwtTokenProvider) {

        this.jwtTokenProvider = jwtTokenProvider;
        this.timeoutMs = timeoutMs;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(riskServiceUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public RiskEvaluationResponse evaluate(RiskEvaluationRequest request) {
        log.info("Dispatching synchronous risk screening for txn {}", request.getTransactionId());
        long start = System.currentTimeMillis();
        try {
            String token = "Bearer " + jwtTokenProvider.generateToken(
                    "SYSTEM_TXN_SERVICE",
                    "system-txn@internal.bank",
                    List.of("ROLE_ADMIN", "ROLE_INTERNAL")
            );

            RiskEvaluationResponse response = restClient.post()
                    .uri("/internal/risk/evaluate")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(RiskEvaluationResponse.class);

            long elapsed = System.currentTimeMillis() - start;
            if (response == null) {
                log.error("Risk engine returned null response in {}ms for txn {}", elapsed, request.getTransactionId());
                throw new SecurityCheckUnavailableException("Risk engine returned null response");
            }
            log.info("Risk screening completed in {}ms for txn {}: score={}, decision={}",
                    elapsed, request.getTransactionId(), response.getScore(), response.getDecision());
            return response;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Risk screening failed after {}ms for txn {}. Failing closed.",
                    elapsed, request.getTransactionId(), ex);
            throw new SecurityCheckUnavailableException("Risk screening unavailable. Failing closed.", ex);
        }
    }
}

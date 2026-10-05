package com.capstone.transaction.client;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.IdempotencyConflictException;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Feign/WebClient/RestClient JSON bridge to the Core Banking System (accounts-service).
 * DIG-14 / T-001.
 */
@Component
@Slf4j
public class CoreBankingClient {

    private final RestClient restClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public CoreBankingClient(
            @Value("${accounts.service.url:http://localhost:8083}") String accountsServiceUrl,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper) {

        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(accountsServiceUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    byte[] bodyBytes = response.getBody().readAllBytes();
                    String responseBody = new String(bodyBytes, StandardCharsets.UTF_8);
                    String detailMessage = extractDetail(responseBody);

                    int statusCode = response.getStatusCode().value();
                    if (statusCode == 422) {
                        throw new InsufficientBalanceException(
                                detailMessage != null ? detailMessage : "Core settlement failed: Insufficient funds");
                    } else if (statusCode == 404) {
                        throw new ResourceNotFoundException(
                                detailMessage != null ? detailMessage : "Resource not found in CBS");
                    } else if (statusCode == 409) {
                        throw new IdempotencyConflictException(
                                detailMessage != null ? detailMessage : "Core settlement idempotency conflict");
                    } else if (statusCode == 400) {
                        throw new IllegalArgumentException(
                                detailMessage != null ? detailMessage : "Bad Request in CBS settlement");
                    } else {
                        throw new IllegalStateException(
                                "Core Banking System error [" + statusCode + "]: " + detailMessage);
                    }
                })
                .build();
    }

    public CoreSettlementResponse settle(CoreSettlementRequest request) {
        String authHeader = resolveInternalToken();
        log.info("Dispatching core settlement to CBS: postingReferenceId={}", request.postingReferenceId());

        CoreSettlementResponse response = restClient.post()
                .uri("/internal/core/settlement")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CoreSettlementResponse.class);

        if (response == null) {
            throw new IllegalStateException("Empty response from CBS settlement for ref " + request.postingReferenceId());
        }
        return response;
    }

    public CoreSettlementResponse getSettlementStatus(String postingReferenceId) {
        String authHeader = resolveInternalToken();

        CoreSettlementResponse response = restClient.get()
                .uri("/internal/core/settlement/{postingReferenceId}", postingReferenceId)
                .header("Authorization", authHeader)
                .retrieve()
                .body(CoreSettlementResponse.class);

        if (response == null) {
            throw new ResourceNotFoundException("Settlement ref " + postingReferenceId + " not found");
        }
        return response;
    }

    public HoldResponse placeHold(String accountId, CreateHoldRequest request) {
        String authHeader = resolveInternalToken();

        HoldResponse response = restClient.post()
                .uri("/internal/accounts/{accountId}/holds", accountId)
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(HoldResponse.class);

        if (response == null) {
            throw new IllegalStateException("Empty response from CBS hold creation for account " + accountId);
        }
        return response;
    }

    public ReleaseHoldResponse releaseHold(String accountId, String holdId) {
        String authHeader = resolveInternalToken();

        ReleaseHoldResponse response = restClient.delete()
                .uri("/internal/accounts/{accountId}/holds/{holdId}", accountId, holdId)
                .header("Authorization", authHeader)
                .retrieve()
                .body(ReleaseHoldResponse.class);

        if (response == null) {
            throw new IllegalStateException("Empty response from CBS hold release for hold " + holdId);
        }
        return response;
    }

    private String resolveInternalToken() {
        String token = jwtTokenProvider.generateToken(
                "SYSTEM_TXN_SERVICE",
                "system-txn@internal.bank",
                List.of("ROLE_ADMIN", "ROLE_INTERNAL")
        );
        return "Bearer " + token;
    }

    private String extractDetail(String json) {
        if (json == null || json.isBlank()) return "Unknown error";
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("detail") && !node.get("detail").isNull()) {
                return node.get("detail").asText();
            }
            if (node.has("message") && !node.get("message").isNull()) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
        }
        return json;
    }
}

package com.capstone.orchestrator.client;

import com.capstone.common.exception.IdempotencyConflictException;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.exception.SecurityCheckUnavailableException;
import com.capstone.orchestrator.dto.CancelPaymentResponse;
import com.capstone.orchestrator.dto.PaymentRequest;
import com.capstone.orchestrator.dto.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * REST Client for Transaction Service (Tier 4 Payment Hub :8084).
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: Channel client for forwarding validated payment requests to Tier 4.
 * - HOW: Uses Spring RestClient to invoke /api/v1/payments on Transaction Service (:8084).
 *        Maps Tier 4 domain status codes (409, 422, 503) into standardized domain exceptions.
 * - WHY: Decouples customer channel presentation from core 7-status lifecycle management,
 *        CAS claims, and CBS settlement executions.
 *
 * TRACE:
 * Channel Orchestrator (:8089) -> TransactionClient -> Transaction Service (:8084)
 */
@Component
@Slf4j
public class TransactionClient {

    private final RestClient restClient;

    public TransactionClient(@Value("${services.transaction-url:http://localhost:8084}") String transactionUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(transactionUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    int status = resp.getStatusCode().value();
                    String body = new String(resp.getBody().readAllBytes());
                    if (status == 409) {
                        String msg = body;
                        if (body.contains("PAYLOAD_MISMATCH")) {
                            msg = "PAYLOAD_MISMATCH: Idempotency key was previously used with a different request payload";
                        } else if (body.contains("REQUEST_IN_PROGRESS")) {
                            msg = "REQUEST_IN_PROGRESS: Request with this idempotency key is already processing";
                        }
                        throw new IdempotencyConflictException(msg.isBlank() ? "Idempotency conflict" : msg);
                    } else if (status == 422) {
                        throw new InsufficientBalanceException(body.isBlank() ? "Unprocessable payment" : body);
                    } else if (status == 404) {
                        throw new ResourceNotFoundException("Transaction not found");
                    } else if (status == 503) {
                        throw new SecurityCheckUnavailableException("Security screening unavailable");
                    } else if (status == 403) {
                        String msg = "Access Denied";
                        int idx = body.indexOf("\"detail\":\"");
                        if (idx != -1) {
                            int end = body.indexOf("\"", idx + 10);
                            if (end != -1) {
                                msg = body.substring(idx + 10, end);
                            }
                        } else if (!body.isBlank()) {
                            msg = body;
                        }
                        throw new org.springframework.security.access.AccessDeniedException(msg);
                    } else if (status == 400) {
                        throw new IllegalArgumentException(body.isBlank() ? "Bad Request" : body);
                    } else {
                        throw new IllegalStateException("Transaction service error [" + status + "]: " + body);
                    }
                })
                .build();
    }

    /**
     * TRACE: Forwards validated payment initiation to Tier 4 Transaction Service.
     */
    public PaymentResponse forwardPayment(PaymentRequest request, String idempotencyKey, String bearerToken) {
        log.info("Forwarding payment request to Transaction Service: sender={}, amount={} {}",
                request.getSenderAccountId(), request.getAmount(), request.getCurrency());

        var req = restClient.post()
                .uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            req.header("X-Idempotency-Key", idempotencyKey);
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            req.header("Authorization", bearerToken);
        }

        return req.retrieve().body(PaymentResponse.class);
    }

    /**
     * TRACE: Forwards customer payment cancellation request to Tier 4.
     */
    public CancelPaymentResponse cancelPayment(String paymentId, String reason, String bearerToken) {
        String url = "/api/v1/payments/" + paymentId + "/cancel"
                + (reason != null ? "?reason=" + java.net.URLEncoder.encode(reason, java.nio.charset.StandardCharsets.UTF_8) : "");
        log.info("TransactionClient: Invoking cancellation at {}", url);
        var requestSpec = restClient.post().uri(url);
        if (bearerToken != null && !bearerToken.isBlank()) {
            requestSpec = requestSpec.header("Authorization", bearerToken);
        }
        var response = requestSpec.retrieve().body(CancelPaymentResponse.class);
        log.info("TransactionClient: Cancel response received: {}", response);
        return response;
    }

    /**
     * TRACE: Queries payment status by ID from Tier 4.
     */
    public PaymentResponse getPayment(String paymentId, String bearerToken) {
        var req = restClient.get()
                .uri("/api/v1/payments/{id}", paymentId);

        if (bearerToken != null && !bearerToken.isBlank()) {
            req.header("Authorization", bearerToken);
        }

        return req.retrieve().body(PaymentResponse.class);
    }

    /**
     * TRACE: Queries payment history for authenticated customer.
     */
    public List<PaymentResponse> getPaymentHistory(String bearerToken) {
        try {
            var req = restClient.get()
                    .uri("/api/v1/payments/history");

            if (bearerToken != null && !bearerToken.isBlank()) {
                req.header("Authorization", bearerToken);
            }

            return req.retrieve().body(new ParameterizedTypeReference<List<PaymentResponse>>() {});
        } catch (Exception ex) {
            log.warn("Failed to fetch payment history: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}


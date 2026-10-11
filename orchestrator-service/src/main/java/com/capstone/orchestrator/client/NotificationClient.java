package com.capstone.orchestrator.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

/**
 * REST Client for Notification Service (Tier 3 Peer :8085).
 *
 * [W-H-Y MODEL]:
 * - WHAT: Fetches customer in-app notifications and alert history.
 * - HOW: Queries GET /api/notifications/{customerId} on Notification Service.
 * - WHY: Enables Home Dashboard aggregation without blocking critical banking journeys
 *        if notification-service is temporarily unavailable (fault isolation).
 */
@Component
@Slf4j
public class NotificationClient {

    private final RestClient restClient;

    public NotificationClient(@Value("${services.notification-url:http://localhost:8085}") String notificationUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(notificationUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * TRACE: Fetches unread notifications for a customer with graceful fallback.
     */
    public List<Object> getNotifications(String customerId, String bearerToken) {
        try {
            var req = restClient.get()
                    .uri("/api/notifications/{customerId}", customerId);

            if (bearerToken != null && !bearerToken.isBlank()) {
                req.header("Authorization", bearerToken);
            }

            List<Object> list = req.retrieve().body(new ParameterizedTypeReference<List<Object>>() {});
            return list != null ? list : Collections.emptyList();
        } catch (Exception ex) {
            // ponytail: Failure of notifications must never crash financial dashboard
            log.warn("Notification service unavailable for customer {}: {}", customerId, ex.getMessage());
            return Collections.emptyList();
        }
    }
}


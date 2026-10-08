package com.capstone.notification.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationDto(
        UUID notifId,
        String customerId,
        String channel,
        String message,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime readAt
) {
    public NotificationDto(UUID notifId, String customerId, String message, String status, OffsetDateTime createdAt) {
        this(notifId, customerId, "IN_APP", message, status, createdAt, null);
    }
}

package com.capstone.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to NOTIFICATION_LOG (PostgreSQL 15+ Channel DB).
 *
 * Owned exclusively by notification-service.
 * Stores omnichannel alert history and customer in-app notifications.
 *
 * status CHECK : PENDING | SENT | FAILED | READ
 * channel CHECK : PUSH | EMAIL | IN_APP
 */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "notif_id", updatable = false, nullable = false)
    private UUID notifId;

    /** References customer_master.customer_id (Oracle) — logical reference. */
    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Builder.Default
    @Column(name = "channel", nullable = false, length = 20)
    private String channel = "IN_APP";

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    /** PENDING | SENT | FAILED | READ */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "read_at")
    private OffsetDateTime readAt;
}

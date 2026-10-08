
package com.capstone.login.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Entity mapping of APP_USER_MASTER (PostgreSQL channel_db).
 *
 * Owned by login-service for credential verification and lockout state,
 * populated initially by registration-service.
 */
@Entity
@Table(name = "app_user_master")
@Getter
@Setter
@NoArgsConstructor
public class AppUser {

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    // References customer_master.customer_id (Oracle) — stored in JWT subject claim
    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    // username == email set at registration; used as login identifier
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "pin_hash", length = 255)
    private String pinHash;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    // CHECK ('ACTIVE','SUSPENDED','LOCKED','DISABLED')
    @Column(name = "active_status", nullable = false, length = 20)
    private String activeStatus;

    @Column(name = "device_hash", length = 255)
    private String deviceHash;

    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}


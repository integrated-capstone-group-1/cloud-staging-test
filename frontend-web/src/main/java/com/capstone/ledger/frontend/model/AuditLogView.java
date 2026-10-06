package com.capstone.ledger.frontend.model;

import java.time.LocalDateTime;

/**
 * Immutable Audit Log Entry Model (DIG-53, DIG-54, DIG-90).
 * Captures all administrative events (lock/freeze, parameter changes, DLQ replay/discard).
 */
public class AuditLogView {

    private String auditId;
    private String action;
    private String performedBy;
    private String targetResource;
    private String targetId;
    private String details;
    private LocalDateTime timestamp;

    public AuditLogView() {
        this.timestamp = LocalDateTime.now();
    }

    public AuditLogView(String auditId, String action, String performedBy,
                        String targetResource, String targetId, String details,
                        LocalDateTime timestamp) {
        this.auditId = auditId;
        this.action = action;
        this.performedBy = performedBy;
        this.targetResource = targetResource;
        this.targetId = targetId;
        this.details = details;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    // Getters and Setters
    public String getAuditId() { return auditId; }
    public void setAuditId(String auditId) { this.auditId = auditId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    public String getTargetResource() { return targetResource; }
    public void setTargetResource(String targetResource) { this.targetResource = targetResource; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}

package com.capstone.ledger.frontend.model;

import java.time.LocalDateTime;

/**
 * Dead Letter Queue (DLQ) Message Model (DIG-90).
 * Supports payload inspection, manual replay with original key, and audited discard with reason.
 */
public class DlqMessageView {

    private String id;
    private String topic;
    private int partition;
    private long offset;
    private String messageKey;
    private String payload;
    private String exceptionMessage;
    private int retryCount;
    private String status; // PENDING, REPLAYED, DISCARDED
    private String discardReason;
    private String actionBy;
    private LocalDateTime actionAt;
    private LocalDateTime timestamp;

    public DlqMessageView() {
        this.status = "PENDING";
        this.timestamp = LocalDateTime.now();
    }

    public DlqMessageView(String id, String topic, int partition, long offset, String messageKey,
                          String payload, String exceptionMessage, int retryCount,
                          String status, LocalDateTime timestamp) {
        this.id = id;
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.messageKey = messageKey;
        this.payload = payload;
        this.exceptionMessage = exceptionMessage;
        this.retryCount = retryCount;
        this.status = status != null ? status : "PENDING";
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    public boolean isPending() {
        return "PENDING".equalsIgnoreCase(status);
    }

    public boolean isReplayed() {
        return "REPLAYED".equalsIgnoreCase(status);
    }

    public boolean isDiscarded() {
        return "DISCARDED".equalsIgnoreCase(status);
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public int getPartition() { return partition; }
    public void setPartition(int partition) { this.partition = partition; }

    public long getOffset() { return offset; }
    public void setOffset(long offset) { this.offset = offset; }

    public String getMessageKey() { return messageKey; }
    public void setMessageKey(String messageKey) { this.messageKey = messageKey; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getExceptionMessage() { return exceptionMessage; }
    public void setExceptionMessage(String exceptionMessage) { this.exceptionMessage = exceptionMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDiscardReason() { return discardReason; }
    public void setDiscardReason(String discardReason) { this.discardReason = discardReason; }

    public String getActionBy() { return actionBy; }
    public void setActionBy(String actionBy) { this.actionBy = actionBy; }

    public LocalDateTime getActionAt() { return actionAt; }
    public void setActionAt(LocalDateTime actionAt) { this.actionAt = actionAt; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}

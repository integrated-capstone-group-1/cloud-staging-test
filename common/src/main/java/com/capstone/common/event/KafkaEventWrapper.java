package com.capstone.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record KafkaEventWrapper<T>(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("aggregate_type") String aggregateType,
        @JsonProperty("aggregate_id") String aggregateId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("payload") T payload
) {}

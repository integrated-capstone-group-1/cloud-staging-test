package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record DeviceBindRequest(
        @NotBlank(message = "Customer ID is required")
        @JsonProperty("customer_id")
        String customerId,

        @NotBlank(message = "Device ID is required")
        @JsonProperty("device_id")
        String deviceId,

        @JsonProperty("device_name")
        String deviceName
) {
    public DeviceBindRequest(String customerId, String deviceId) {
        this(customerId, deviceId, null);
    }
}

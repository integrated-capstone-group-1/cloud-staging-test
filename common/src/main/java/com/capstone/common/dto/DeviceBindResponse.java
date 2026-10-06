package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeviceBindResponse(
        @JsonProperty("customer_id")
        String customerId,

        @JsonProperty("device_id")
        String deviceId,

        String status,

        String message
) {
    public static DeviceBindResponse bound(String customerId, String deviceId) {
        return new DeviceBindResponse(customerId, deviceId, "BOUND", "Device bound successfully");
    }

    public static DeviceBindResponse verified(String customerId, String deviceId) {
        return new DeviceBindResponse(customerId, deviceId, "VERIFIED", "Device matches bound device");
    }
}

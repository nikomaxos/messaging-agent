package com.messagingagent.kafka;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsDeliveryResultEvent {

    public enum Result {
        DELIVERED,      // RCS delivery confirmed
        NO_RCS,         // Target does not support RCS
        TIMEOUT,        // Device did not respond in time
        ERROR           // Generic error
    }

    private String correlationId;
    private String destinationAddress;
    private Result result;
    private String deviceId;
    private String errorDetail;
    private long timestampMs;
}

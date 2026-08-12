package com.messagingagent.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RcsOutboundEvent {
    private String smppMessageId;
    private Long deviceId;
    private String matrixId;
    private String destinationAddress;
    private String messageText;
    private Long timestampMs;
}

package com.messagingagent.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmppOutboundEvent {
    private Long messageLogId;
    private Long supplierId;
    private String sourceAddress;
    private String destinationAddress;
    private String messageText;
    private String smppMessageId; // Original message ID for sending DLR
}

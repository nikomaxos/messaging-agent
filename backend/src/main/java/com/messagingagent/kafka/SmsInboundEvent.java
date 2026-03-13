package com.messagingagent.kafka;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmsInboundEvent {
    private String systemId;
    private String sourceAddress;
    private String destinationAddress;
    private String messageText;
    private byte dataCoding;
    private String correlationId;
    private long timestampMs;
}

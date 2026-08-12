package com.messagingagent.dto;

import com.messagingagent.model.SmscSupplier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmscSupplierDto {
    private SmscSupplier supplier;
    private Long uptimeSeconds;         // Connection duration if bound, null otherwise
    private boolean connected;
    private long totalMessages;
    private long dlrsReceived;
    private long failed;
    private long inQueue;
}

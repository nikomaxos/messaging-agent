package com.messagingagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemLogEvent {
    private String level;    // "INFO", "WARN", "ERROR"
    private String device;   // The component (e.g. "SMPP Server", "SMPP Supplier", "Client X")
    private String event;    // The summary (e.g. "Bind requested")
    private String detail;   // Optional longer detail
}

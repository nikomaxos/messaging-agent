package com.messagingagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmppSessionDto {
    private String sessionId;
    private String bindType;
    private long uptimeSeconds;
}

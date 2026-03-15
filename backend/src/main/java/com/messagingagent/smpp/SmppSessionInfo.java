package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppServerSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmppSessionInfo {
    private String sessionId;
    private SmppServerSession session;
    private Instant boundAt;
}

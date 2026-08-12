package com.messagingagent.dto;

import com.messagingagent.model.SmppClient;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmppClientDto {
    private Long id;
    private String name;
    private String systemId;
    private boolean active;
    private Instant createdAt;
    private List<SmppSessionDto> activeSessions;

    public static SmppClientDto fromEntity(SmppClient client, List<SmppSessionDto> activeSessions) {
        return new SmppClientDto(
                client.getId(),
                client.getName(),
                client.getSystemId(),
                client.isActive(),
                client.getCreatedAt(),
                activeSessions
        );
    }
}

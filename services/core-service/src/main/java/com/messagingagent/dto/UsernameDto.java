package com.messagingagent.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class UsernameDto {
    private Long id;
    private String username;
    private Long accountId;
    private String whitelistedIps;
    private boolean enforceIpWhitelist;
    private boolean smppEnabled;
    private boolean apiEnabled;
    private boolean webEnabled;
    private boolean banned;
    private Instant createdAt;
    private Instant updatedAt;
}

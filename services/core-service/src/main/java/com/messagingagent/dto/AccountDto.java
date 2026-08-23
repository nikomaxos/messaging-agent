package com.messagingagent.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class AccountDto {
    private Long id;
    private String name;
    private String type;
    private String companyName;
    private String vatNumber;
    private String address;
    private String email;
    private String contactPerson;
    private String whitelistedIps;
    private boolean enforceIpWhitelist;
    private boolean smppEnabled;
    private boolean apiEnabled;
    private boolean webEnabled;
    private Instant createdAt;
    private Instant updatedAt;
}

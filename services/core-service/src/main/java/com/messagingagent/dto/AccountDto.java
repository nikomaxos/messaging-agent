package com.messagingagent.dto;

import lombok.Data;
import java.time.Instant;
import java.util.List;

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
    private List<UsernameDto> usernames;
    private Instant createdAt;
    private Instant updatedAt;
}

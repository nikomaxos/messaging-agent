package com.messagingagent.dto;

import lombok.Data;

@Data
public class RuleActionDto {
    private Long id;
    private String actionType;
    private String actionValue;
}

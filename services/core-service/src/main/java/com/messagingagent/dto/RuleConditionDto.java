package com.messagingagent.dto;

import lombok.Data;

@Data
public class RuleConditionDto {
    private Long id;
    private String field;
    private String operator;
    private String value;
}

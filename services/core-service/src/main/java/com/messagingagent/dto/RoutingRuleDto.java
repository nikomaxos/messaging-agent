package com.messagingagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class RoutingRuleDto {
    private Long id;
    private String name;
    private String description;
    private Integer priority;
    private boolean active;
    private boolean enableRoutingPerCountryPrefix;
    private List<RuleConditionDto> conditions;
    private List<RuleActionDto> actions;
}

package com.messagingagent.service;

import com.messagingagent.dto.RoutingRuleDto;
import com.messagingagent.dto.RuleActionDto;
import com.messagingagent.dto.RuleConditionDto;
import com.messagingagent.model.RoutingRule;
import com.messagingagent.model.RuleAction;
import com.messagingagent.model.RuleCondition;
import com.messagingagent.repository.RoutingRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingRuleService {

    private final RoutingRuleRepository routingRuleRepository;

    public List<RoutingRuleDto> getAllRules() {
        return routingRuleRepository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }
    
    public List<RoutingRule> getActiveRulesSorted() {
        return routingRuleRepository.findByIsActiveTrueOrderByPriorityAsc();
    }

    @Transactional
    public RoutingRuleDto createRule(RoutingRuleDto dto) {
        RoutingRule rule = new RoutingRule();
        updateEntityFromDto(rule, dto);
        return mapToDto(routingRuleRepository.save(rule));
    }

    @Transactional
    public RoutingRuleDto updateRule(Long id, RoutingRuleDto dto) {
        RoutingRule rule = routingRuleRepository.findById(id).orElseThrow(() -> new RuntimeException("Rule not found"));
        rule.getConditions().clear();
        rule.getActions().clear();
        updateEntityFromDto(rule, dto);
        return mapToDto(routingRuleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        routingRuleRepository.deleteById(id);
    }
    
    // Evaluation Logic for an incoming message
    // Returns a Trace event or applies changes to the map directly
    public String evaluateRule(RoutingRule rule, java.util.Map<String, Object> eventData) {
        boolean matches = true;
        
        for (RuleCondition condition : rule.getConditions()) {
            String targetValue = extractTargetValue(eventData, condition.getField());
            if (targetValue == null) targetValue = "";
            
            if (!evaluateCondition(condition, targetValue)) {
                matches = false;
                break;
            }
        }
        
        if (matches) {
            log.info("Message matched Routing Rule: {} (ID: {})", rule.getName(), rule.getId());
            StringBuilder traceBuilder = new StringBuilder("Matched Rule '").append(rule.getName()).append("': ");
            
            for (RuleAction action : rule.getActions()) {
                applyAction(action, eventData, traceBuilder);
            }
            
            return traceBuilder.toString();
        }
        
        return null;
    }

    private String extractTargetValue(java.util.Map<String, Object> eventData, String field) {
        switch (field.toUpperCase()) {
            case "SOURCE_ADDRESS":
                return (String) eventData.get("sourceAddress");
            case "DESTINATION_ADDRESS":
                return (String) eventData.get("destinationAddress");
            case "MESSAGE_TEXT":
                return (String) eventData.get("messageText");
            case "SYSTEM_ID":
                return (String) eventData.get("systemId");
            default:
                return "";
        }
    }

    private boolean evaluateCondition(RuleCondition condition, String targetValue) {
        try {
            switch (condition.getOperator().toUpperCase()) {
                case "MATCHES_REGEX":
                    return Pattern.compile(condition.getValue()).matcher(targetValue).find();
                case "EQUALS":
                    return targetValue.equals(condition.getValue());
                case "CONTAINS":
                    return targetValue.contains(condition.getValue());
                case "STARTS_WITH":
                    return targetValue.startsWith(condition.getValue());
                default:
                    return false;
            }
        } catch (Exception e) {
            log.error("Failed to evaluate condition operator {} regex {}", condition.getOperator(), condition.getValue(), e);
            return false;
        }
    }

    private void applyAction(RuleAction action, java.util.Map<String, Object> eventData, StringBuilder traceBuilder) {
        try {
            switch (action.getActionType().toUpperCase()) {
                case "REWRITE_SOURCE":
                    String newSource = applyRegexReplace(eventData.get("sourceAddress"), action.getActionValue());
                    eventData.put("sourceAddress", newSource);
                    traceBuilder.append("[Rewrote Source to '").append(newSource).append("'] ");
                    break;
                case "REWRITE_TEXT":
                    String newText = applyRegexReplace(eventData.get("messageText"), action.getActionValue());
                    eventData.put("messageText", newText);
                    traceBuilder.append("[Rewrote Text] ");
                    break;
                case "OVERRIDE_SMSC":
                    eventData.put("overrideSmscId", action.getActionValue());
                    traceBuilder.append("[Forced SMSC ").append(action.getActionValue()).append("] ");
                    break;
                case "FAKE_DLR":
                    eventData.put("fakeDlrStatus", action.getActionValue());
                    eventData.put("terminateRouting", true);
                    traceBuilder.append("[Fake DLR '").append(action.getActionValue()).append("'] ");
                    break;
                case "DROP":
                    eventData.put("dropMessage", true);
                    eventData.put("terminateRouting", true);
                    traceBuilder.append("[Dropped] ");
                    break;
            }
        } catch (Exception e) {
            log.error("Failed to apply action {}", action.getActionType(), e);
        }
    }

    // ActionValue format for Regex rewrite: "regex|||replacement" OR just "replacement" if simple
    private String applyRegexReplace(Object original, String actionValue) {
        if (original == null) return "";
        String str = (String) original;
        if (actionValue.contains("|||")) {
            String[] parts = actionValue.split("\\|\\|\\|");
            if (parts.length >= 2) {
                return str.replaceAll(parts[0], parts[1]);
            }
        }
        return actionValue; // Simple complete overwrite
    }

    private void updateEntityFromDto(RoutingRule rule, RoutingRuleDto dto) {
        rule.setName(dto.getName());
        rule.setDescription(dto.getDescription());
        rule.setPriority(dto.getPriority());
        rule.setActive(dto.isActive());
        rule.setEnableRoutingPerCountryPrefix(dto.isEnableRoutingPerCountryPrefix());

        if (dto.getConditions() != null) {
            for (RuleConditionDto cDto : dto.getConditions()) {
                RuleCondition condition = new RuleCondition();
                condition.setField(cDto.getField());
                condition.setOperator(cDto.getOperator());
                condition.setValue(cDto.getValue());
                rule.addCondition(condition);
            }
        }

        if (dto.getActions() != null) {
            for (RuleActionDto aDto : dto.getActions()) {
                RuleAction action = new RuleAction();
                action.setActionType(aDto.getActionType());
                action.setActionValue(aDto.getActionValue());
                rule.addAction(action);
            }
        }
    }

    private RoutingRuleDto mapToDto(RoutingRule rule) {
        RoutingRuleDto dto = new RoutingRuleDto();
        dto.setId(rule.getId());
        dto.setName(rule.getName());
        dto.setDescription(rule.getDescription());
        dto.setPriority(rule.getPriority());
        dto.setActive(rule.isActive());
        dto.setEnableRoutingPerCountryPrefix(rule.isEnableRoutingPerCountryPrefix());

        dto.setConditions(rule.getConditions().stream().map(c -> {
            RuleConditionDto cDto = new RuleConditionDto();
            cDto.setId(c.getId());
            cDto.setField(c.getField());
            cDto.setOperator(c.getOperator());
            cDto.setValue(c.getValue());
            return cDto;
        }).collect(Collectors.toList()));

        dto.setActions(rule.getActions().stream().map(a -> {
            RuleActionDto aDto = new RuleActionDto();
            aDto.setId(a.getId());
            aDto.setActionType(a.getActionType());
            aDto.setActionValue(a.getActionValue());
            return aDto;
        }).collect(Collectors.toList()));

        return dto;
    }
}

package com.messagingagent.controller;

import com.messagingagent.dto.RoutingRuleDto;
import com.messagingagent.service.RoutingRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routing/rules")
@RequiredArgsConstructor
public class RoutingRuleController {

    private final RoutingRuleService routingRuleService;

    @GetMapping
    public ResponseEntity<List<RoutingRuleDto>> getAllRules() {
        return ResponseEntity.ok(routingRuleService.getAllRules());
    }

    @PostMapping
    public ResponseEntity<RoutingRuleDto> createRule(@RequestBody RoutingRuleDto dto) {
        return ResponseEntity.ok(routingRuleService.createRule(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoutingRuleDto> updateRule(@PathVariable Long id, @RequestBody RoutingRuleDto dto) {
        return ResponseEntity.ok(routingRuleService.updateRule(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        routingRuleService.deleteRule(id);
        return ResponseEntity.ok().build();
    }

    // A test endpoint for evaluating a rule against a payload (dry-run)
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testRule(@RequestBody Map<String, Object> payload) {
        // We expect the payload to contain the message attributes
        // We will fetch all active rules and run them against the payload
        List<com.messagingagent.model.RoutingRule> rules = routingRuleService.getActiveRulesSorted();
        StringBuilder trace = new StringBuilder();
        
        for (com.messagingagent.model.RoutingRule rule : rules) {
            String ruleTrace = routingRuleService.evaluateRule(rule, payload);
            if (ruleTrace != null) {
                trace.append(ruleTrace).append(" | ");
                if (payload.containsKey("terminateRouting") && (Boolean) payload.get("terminateRouting")) {
                    trace.append("Terminated evaluation. ");
                    break;
                }
            }
        }
        
        payload.put("_trace", trace.toString());
        return ResponseEntity.ok(payload);
    }
}

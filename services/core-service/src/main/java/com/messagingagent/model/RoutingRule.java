package com.messagingagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "routing_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    @Builder.Default
    private boolean enableRoutingPerCountryPrefix = false;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonIgnoreProperties("rule")
    @Builder.Default
    private List<RuleCondition> conditions = new ArrayList<>();

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonIgnoreProperties("rule")
    @Builder.Default
    private List<RuleAction> actions = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
    
    public void addCondition(RuleCondition condition) {
        conditions.add(condition);
        condition.setRule(this);
    }

    public void addAction(RuleAction action) {
        actions.add(action);
        action.setRule(this);
    }
}

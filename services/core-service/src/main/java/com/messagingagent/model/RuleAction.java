package com.messagingagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import jakarta.persistence.*;

@Entity
@Table(name = "routing_rule_action")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuleAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routing_rule_id", nullable = false)
    @JsonIgnore
    private RoutingRule rule;

    @Column(nullable = false, length = 50)
    private String actionType;

    @Column(columnDefinition = "TEXT")
    private String actionValue;
}

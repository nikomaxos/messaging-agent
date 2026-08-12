package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String username;

    @Column(nullable = false, length = 50)
    private String action; // LOGIN, CONFIG_CHANGE, RESUBMIT, DELETE, CREATE, UPDATE

    @Column(name = "entity_type", length = 50)
    private String entityType; // Device, SmscSupplier, AiAgentConfig, etc.

    @Column(name = "entity_id", length = 50)
    private String entityId;

    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}

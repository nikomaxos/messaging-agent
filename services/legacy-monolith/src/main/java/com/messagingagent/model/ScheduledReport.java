package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "scheduled_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20)
    @Builder.Default
    private String period = "DAILY";

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant generatedAt = Instant.now();

    @Column(nullable = false, columnDefinition = "jsonb")
    private String reportJson;
}

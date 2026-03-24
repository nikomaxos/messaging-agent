package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notification_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationAlert {

    public enum Severity {
        INFO, WARNING, CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private NotificationConfig.AlertType type;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Severity severity = Severity.WARNING;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "metric_value")
    private Double metricValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "acknowledged")
    @Builder.Default
    private boolean acknowledged = false;

    @CreationTimestamp
    private Instant createdAt;
}

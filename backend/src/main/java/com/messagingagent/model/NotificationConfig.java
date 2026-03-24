package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "notification_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationConfig {

    public enum AlertType {
        LOW_DELIVERY_RATE,
        HIGH_LATENCY,
        QUEUE_BUILDUP,
        DEVICE_OFFLINE,
        SMSC_DISCONNECT,
        SELF_HEALING_ESCALATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertType type;

    @Column(nullable = false)
    @Builder.Default
    private double threshold = 50.0; // percentage, seconds, or count depending on type

    @Column(name = "cooldown_minutes", nullable = false)
    @Builder.Default
    private int cooldownMinutes = 15;

    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @CreationTimestamp
    private Instant createdAt;
}

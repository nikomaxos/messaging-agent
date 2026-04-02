package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

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
        SELF_HEALING_ESCALATION,
        POSSIBLE_AIT_TRAFFIC
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "notification_config_channels", joinColumns = @JoinColumn(name = "config_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "channel")
    @Builder.Default
    private Set<NotificationChannel> channels = new HashSet<>();

    @Column(name = "auto_block")
    @Builder.Default
    private boolean autoBlock = false;

    @Column(name = "auto_block_action", length = 30)
    @Builder.Default
    private String autoBlockAction = "REJECT_INVDSTADR";

    @Column(name = "alert_device_group_id")
    private Long alertDeviceGroupId;

    @Column(name = "alert_smpp_supplier_id")
    private Long alertSmppSupplierId;

    @CreationTimestamp
    private Instant createdAt;
}

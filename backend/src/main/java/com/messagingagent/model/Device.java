package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Device {

    public enum Status {
        ONLINE, OFFLINE, BUSY, MAINTENANCE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(unique = true, length = 20)
    private String imei;

    @Column(unique = true, length = 100)
    private String registrationToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.OFFLINE;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "group_id")
    private DeviceGroup group;

    // Heartbeat data
    private Integer batteryPercent;
    
    @Column(name = "is_charging")
    private Boolean isCharging;
    
    @Column(name = "sim_iccid", length = 50)
    private String simIccid;
    
    @Column(name = "phone_number", length = 50)
    private String phoneNumber;
    private Integer wifiSignalDbm;
    private Integer gsmSignalDbm;
    private Integer gsmSignalAsu;
    private String networkOperator;
    private Boolean rcsCapable;
    @Builder.Default
    private Boolean autoRebootEnabled = false;
    @Builder.Default
    @Column(name = "auto_purge", length = 20)
    private String autoPurge = "OFF";
    @Column(name = "last_purged_at")
    private Instant lastPurgedAt;
    private String activeNetworkType;
    private String apkVersion;
    private String apkUpdateStatus;
    @Builder.Default
    @Column(name = "autostart_pinned")
    private Boolean autostartPinned = false;
    @Builder.Default
    @Column(name = "silent_mode")
    private Boolean silentMode = false;
    @Builder.Default
    @Column(name = "call_block_enabled")
    private Boolean callBlockEnabled = false;
    @Builder.Default
    @Column(name = "self_healing_enabled")
    private Boolean selfHealingEnabled = false;
    @Builder.Default
    @Column(name = "send_interval_seconds")
    private Double sendIntervalSeconds = 0.0;
    @Column(name = "adb_wifi_address")
    private String adbWifiAddress;
    @Column(name = "last_dispatched_at")
    private Instant lastDispatchedAt;

    // Self-healing escalation tracking
    @Column(name = "self_healing_reboot_count")
    @Builder.Default
    private Integer selfHealingRebootCount = 0;
    @Column(name = "last_self_healing_at")
    private Instant lastSelfHealingAt;

    // Sliding Window Token Bucket for Multiplexed DLRs (Phase 6)
    @Column(name = "in_flight_dispatches")
    @Builder.Default
    private Integer inFlightDispatches = 0;

    @Transient
    public static final int MAX_CONCURRENT_DISPATCHES = 5;

    // GPS location (reported via heartbeat)
    private Double latitude;
    private Double longitude;

    private Instant lastHeartbeat;
    private String sessionId;        // WebSocket session ID when online
    private Instant connectedAt;     // Timestamp of last connection

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private DeviceGroup group;

    // Heartbeat data
    private Integer batteryPercent;
    private Integer wifiSignalDbm;
    private Integer gsmSignalDbm;
    private Integer gsmSignalAsu;
    private String networkOperator;
    private Boolean rcsCapable;

    private Instant lastHeartbeat;
    private String sessionId;        // WebSocket session ID when online

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "smpp_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmppConfig {

    public enum BindType {
        TRANSCEIVER, TRANSMITTER, RECEIVER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String systemId;

    @Column(nullable = false, length = 9)
    private String password;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BindType bindType = BindType.TRANSCEIVER;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Reference to the DeviceGroup (virtual SMSC) to use for this endpoint */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_group_id")
    private DeviceGroup deviceGroup;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

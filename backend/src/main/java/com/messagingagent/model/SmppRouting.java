package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "smpp_routing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmppRouting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "smpp_client_id")
    private SmppClient smppClient;

    @ManyToOne(optional = false)
    @JoinColumn(name = "device_group_id")
    private DeviceGroup deviceGroup;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

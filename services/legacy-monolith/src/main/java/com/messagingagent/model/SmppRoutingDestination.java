package com.messagingagent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "smpp_routing_destination")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmppRoutingDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "smpp_routing_id")
    @JsonIgnore
    private SmppRouting smppRouting;

    @ManyToOne(optional = false)
    @JoinColumn(name = "device_group_id")
    private DeviceGroup deviceGroup;

    @Column(name = "weight_percent", nullable = false)
    @Builder.Default
    private int weightPercent = 100;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_smsc_id")
    private SmscSupplier fallbackSmsc;

}

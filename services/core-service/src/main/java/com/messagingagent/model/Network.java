package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "network")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Network {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "country_id", nullable = false)
    private Country country;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "network_mncs", joinColumns = @JoinColumn(name = "network_id"))
    @Column(name = "mnc")
    @Builder.Default
    private List<String> mncs = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "operating_status")
    @Builder.Default
    private OperatingStatus operatingStatus = OperatingStatus.ACTIVE;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "network_prefixes", joinColumns = @JoinColumn(name = "network_id"))
    @Column(name = "prefix")
    @Builder.Default
    private List<String> prefixes = new ArrayList<>();
}

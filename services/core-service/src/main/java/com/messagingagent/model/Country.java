package com.messagingagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "country")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Country {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(length = 5)
    private String isoCode;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "country_mccs", joinColumns = @JoinColumn(name = "country_id"))
    @Column(name = "mcc")
    @Builder.Default
    private List<String> mccs = new ArrayList<>();

    @OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonIgnoreProperties("country")
    @Builder.Default
    private List<Network> networks = new ArrayList<>();

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "quiet_hours_start")
    private String quietHoursStart;

    @Column(name = "quiet_hours_end")
    private String quietHoursEnd;

    @Column(name = "has_dnd_list")
    @Builder.Default
    private boolean hasDndList = false;

    public void addNetwork(Network network) {
        networks.add(network);
        network.setCountry(this);
    }
}

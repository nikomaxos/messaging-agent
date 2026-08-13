package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "country_prefixes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CountryPrefix {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String countryName;

    @Column(nullable = false)
    private String prefix; // e.g., "3069" for Greece Mobile

    @Column(nullable = false)
    private String networkName; // e.g., "Vodafone GR"

    @Column(name = "mcc", length = 5)
    private String mcc;

    @Column(name = "mnc", length = 5)
    private String mnc;

    @Column(name = "iso", length = 2)
    private String iso;

    @Column(nullable = false)
    private boolean active = true;
}

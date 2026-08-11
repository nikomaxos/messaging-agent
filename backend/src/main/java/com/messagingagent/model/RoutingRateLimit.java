package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "routing_rate_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingRateLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_profile_id", length = 50)
    private String customerProfileId; // Maps to SMPP systemId

    @Column(name = "country_code", length = 10)
    private String countryCode;

    @Column(name = "network_id", length = 50)
    private String networkId;
    
    @Column(name = "supplier_id", length = 50)
    private String supplierId;

    @Column(name = "speed_tps", nullable = false, precision = 10, scale = 4)
    private BigDecimal speedTps;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}

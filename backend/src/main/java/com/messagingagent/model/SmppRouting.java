package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

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

    @OneToMany(mappedBy = "smppRouting", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private Set<SmppRoutingDestination> destinations = new HashSet<>();

    @Column(name = "load_balancer_enabled", nullable = false)
    @Builder.Default
    private boolean loadBalancerEnabled = false;

    @Column(name = "resend_enabled", nullable = false)
    @Builder.Default
    private boolean resendEnabled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_smsc_id")
    private SmscSupplier fallbackSmsc;

    @Column(name = "resend_trigger", length = 20)
    private String resendTrigger; // e.g. "RCS_FAILED", "FAILED"

    @Column(name = "rcs_expiration_seconds")
    private Integer rcsExpirationSeconds;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

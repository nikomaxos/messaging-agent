package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "username")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Username {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonBackReference
    private Account account;

    @Column(name = "whitelisted_ips", columnDefinition = "TEXT")
    private String whitelistedIps; // Comma separated

    @Column(name = "enforce_ip_whitelist", nullable = false)
    @Builder.Default
    private boolean enforceIpWhitelist = false;

    @Column(name = "smpp_enabled", nullable = false)
    @Builder.Default
    private boolean smppEnabled = true;

    @Column(name = "api_enabled", nullable = false)
    @Builder.Default
    private boolean apiEnabled = false;

    @Column(name = "web_enabled", nullable = false)
    @Builder.Default
    private boolean webEnabled = false;

    @Column(name = "banned", nullable = false)
    @Builder.Default
    private boolean banned = false;

    @OneToMany(mappedBy = "username", cascade = CascadeType.ALL, orphanRemoval = true)
    @com.fasterxml.jackson.annotation.JsonManagedReference
    private List<SmppClient> smppClients;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

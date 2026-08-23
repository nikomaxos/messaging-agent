package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String type = "CUSTOMER"; // CUSTOMER, SUPPLIER, BILATERAL

    @Column(name = "company_name", length = 150)
    private String companyName;

    @Column(name = "vat_number", length = 50)
    private String vatNumber;

    @Column(length = 255)
    private String address;

    @Column(length = 150)
    private String email;

    @Column(name = "contact_person", length = 100)
    private String contactPerson;

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

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

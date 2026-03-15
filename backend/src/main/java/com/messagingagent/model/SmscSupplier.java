package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "smsc_supplier")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmscSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(nullable = false, length = 64)
    private String systemId;

    @Column(nullable = false, length = 64)
    private String password;

    @Column(length = 64)
    private String systemType;

    @Column(nullable = false, length = 32)
    @Builder.Default
    private String bindType = "TRANSCEIVER"; // TRANSMITTER, RECEIVER, TRANSCEIVER

    @Column(length = 64)
    private String addressRange;

    @Column
    @Builder.Default
    private int sourceTon = 0;

    @Column
    @Builder.Default
    private int sourceNpi = 0;

    @Column
    @Builder.Default
    private int destTon = 0;

    @Column
    @Builder.Default
    private int destNpi = 0;

    @Column
    @Builder.Default
    private int throughput = 0; // 0 means unlimited

    @Column
    @Builder.Default
    private int enquireLinkInterval = 30000;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

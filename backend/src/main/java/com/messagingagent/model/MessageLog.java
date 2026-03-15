package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "message_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageLog {

    public enum Status {
        RECEIVED,       // PDU received from upstream
        DISPATCHED,     // Sent to Android device
        DELIVERED,      // RCS delivery confirmed
        RCS_FAILED,     // Target has no RCS
        FAILED          // Generic failure
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String smppMessageId;

    @Column(length = 20)
    private String sourceAddress;

    @Column(length = 20)
    private String destinationAddress;

    @Column(length = 4000)
    private String messageText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.RECEIVED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Device device;

    @Column(length = 1000)
    private String errorDetail;

    @CreationTimestamp
    private Instant createdAt;

    private Instant deliveredAt;
    
    private Instant fallbackStartedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "smsc_supplier_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SmscSupplier smscSupplier;

    private Instant rcsExpiresAt;

    @Column(length = 20)
    private String resendTrigger;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fallback_smsc_id")
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private SmscSupplier fallbackSmsc;
}

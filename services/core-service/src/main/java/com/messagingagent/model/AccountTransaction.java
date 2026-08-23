package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false, precision = 15, scale = 5)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String type; // TOPUP, ADJUSTMENT, DEDUCTION

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    private Instant createdAt;
}

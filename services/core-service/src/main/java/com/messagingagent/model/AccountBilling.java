package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_billing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountBilling {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "account_id")
    private Account account;

    @Column(name = "billing_type", nullable = false, length = 20)
    @Builder.Default
    private String billingType = "POSTPAID"; // PREPAID or POSTPAID

    @Column(nullable = false, precision = 15, scale = 5)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "credit_limit", nullable = false, precision = 15, scale = 5)
    @Builder.Default
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tariff_plan_id")
    private TariffPlan tariffPlan;

    @UpdateTimestamp
    private Instant updatedAt;
}

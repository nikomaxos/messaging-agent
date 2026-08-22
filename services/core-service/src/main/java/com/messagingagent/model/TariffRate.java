package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "tariff_rate", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"plan_id", "prefix"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TariffRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private TariffPlan tariffPlan;

    @Column(nullable = false, length = 20)
    private String prefix;

    @Column(nullable = false, precision = 10, scale = 5)
    private BigDecimal rate;

    @CreationTimestamp
    private Instant createdAt;
}

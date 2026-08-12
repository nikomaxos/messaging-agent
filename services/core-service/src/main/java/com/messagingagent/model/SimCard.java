package com.messagingagent.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "sim_card")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String iccid;

    @Column(length = 50)
    private String imsi;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "carrier_name", length = 100)
    private String carrierName;

    // Optional IMEI recorded corresponding to that specific slot at the time
    @Column(length = 20)
    private String imei;

    @Column(name = "slot_index")
    private Integer slotIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    @JsonBackReference
    private Device device;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}

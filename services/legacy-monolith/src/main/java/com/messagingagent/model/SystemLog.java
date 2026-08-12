package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "system_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String level;

    @Column(nullable = false, length = 255)
    private String device;

    @Column(nullable = false, length = 255)
    private String event;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;
}

package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "dead_letter_message")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterMessage {

    public enum DlqStatus {
        DEAD, RETRIED, DISCARDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_message_id")
    private Long originalMessageId;

    @Column(length = 20)
    private String sourceAddress;

    @Column(length = 20)
    private String destinationAddress;

    @Column(length = 4000)
    private String messageText;

    @Column(length = 1000)
    private String failureReason;

    @Builder.Default
    private int retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DlqStatus status = DlqStatus.DEAD;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant retriedAt;
    private Instant discardedAt;
}

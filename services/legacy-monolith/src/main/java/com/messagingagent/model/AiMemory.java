package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Long-term memory for the AI Agent.
 * Stores extracted takeaways and key points from conversations.
 */
@Entity
@Table(name = "ai_memory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "key_points", nullable = false, columnDefinition = "TEXT")
    private String keyPoints;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

package com.messagingagent.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "ai_agent_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAgentConfig {

    public enum Provider {
        GEMINI, CLAUDE, OPENAI
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Provider provider = Provider.GEMINI;

    @Column(name = "api_key", length = 500)
    @Convert(converter = com.messagingagent.security.EncryptedStringConverter.class)
    private String apiKey; // encrypted at rest via AES-256-GCM

    @Column(name = "model_name", length = 100)
    @Builder.Default
    private String modelName = "gemini-2.0-flash";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @UpdateTimestamp
    private Instant updatedAt;
}

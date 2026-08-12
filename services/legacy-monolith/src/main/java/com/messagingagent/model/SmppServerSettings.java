package com.messagingagent.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "smpp_server_settings")
@Data
public class SmppServerSettings {

    @Id
    private Long id = 1L; // Single-row table

    private String host;
    private int port;
    private int maxConnections;
    private int enquireLinkTimeout;
}

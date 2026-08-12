CREATE TABLE notification_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    type VARCHAR(30) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL DEFAULT 50.0,
    cooldown_minutes INT NOT NULL DEFAULT 15,
    last_triggered_at TIMESTAMP,
    auto_block BOOLEAN DEFAULT FALSE,
    alert_device_group_id BIGINT,
    alert_smpp_supplier_id BIGINT,
    alert_matrix_room_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notification_config_channels (
    config_id BIGINT NOT NULL,
    channel VARCHAR(255) NOT NULL,
    CONSTRAINT fk_notification_config_channels_config FOREIGN KEY (config_id) REFERENCES notification_config(id) ON DELETE CASCADE
);

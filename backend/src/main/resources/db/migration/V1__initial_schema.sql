-- V1__initial_schema.sql
CREATE TABLE device_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE device (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(100) NOT NULL,
    imei                VARCHAR(20) UNIQUE,
    registration_token  VARCHAR(100) UNIQUE,
    status              VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    group_id            BIGINT REFERENCES device_group(id),
    battery_percent     INTEGER,
    wifi_signal_dbm     INTEGER,
    gsm_signal_dbm      INTEGER,
    gsm_signal_asu      INTEGER,
    network_operator    VARCHAR(50),
    rcs_capable         BOOLEAN,
    last_heartbeat      TIMESTAMP WITH TIME ZONE,
    session_id          VARCHAR(255),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE smpp_config (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    system_id       VARCHAR(16) NOT NULL,
    password        VARCHAR(9) NOT NULL,
    host            VARCHAR(255) NOT NULL,
    port            INTEGER NOT NULL,
    bind_type       VARCHAR(20) NOT NULL DEFAULT 'TRANSCEIVER',
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    device_group_id BIGINT REFERENCES device_group(id),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE message_log (
    id                  BIGSERIAL PRIMARY KEY,
    smpp_message_id     VARCHAR(50),
    source_address      VARCHAR(20),
    destination_address VARCHAR(20),
    message_text        VARCHAR(160),
    status              VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    device_id           BIGINT REFERENCES device(id),
    error_detail        VARCHAR(500),
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    delivered_at        TIMESTAMP WITH TIME ZONE
);

CREATE TABLE app_user (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50) NOT NULL UNIQUE,
    password    VARCHAR(200) NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Default admin user (password: admin123 — bcrypt cost 12, generated with Spring BCryptPasswordEncoder)
INSERT INTO app_user (username, password, role)
VALUES ('admin', '$2a$12$ClVx/w1SvKesl3t9uFG82OFJD6IzTEe3ko4AfNZu1fmcKAwWMZvNK', 'ADMIN');

CREATE INDEX idx_device_group_id ON device(group_id);
CREATE INDEX idx_device_status ON device(status);
CREATE INDEX idx_message_log_status ON message_log(status);
CREATE INDEX idx_message_log_created ON message_log(created_at DESC);

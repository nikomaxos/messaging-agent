-- V2__smpp_client_routing.sql
DROP TABLE IF EXISTS smpp_config;

CREATE TABLE smpp_client (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    system_id   VARCHAR(16) NOT NULL UNIQUE,
    password    VARCHAR(64) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE smpp_routing (
    id              BIGSERIAL PRIMARY KEY,
    smpp_client_id  BIGINT NOT NULL REFERENCES smpp_client(id) ON DELETE CASCADE,
    device_group_id BIGINT NOT NULL REFERENCES device_group(id) ON DELETE CASCADE,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

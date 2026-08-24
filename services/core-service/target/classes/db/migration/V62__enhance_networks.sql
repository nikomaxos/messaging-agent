-- V62__enhance_networks.sql
ALTER TABLE network ADD COLUMN operating_status VARCHAR(255) DEFAULT 'ACTIVE';
ALTER TABLE network ADD COLUMN notes TEXT;

CREATE TABLE network_prefixes (
    network_id BIGINT NOT NULL,
    prefix VARCHAR(255),
    CONSTRAINT fk_network_prefix FOREIGN KEY (network_id) REFERENCES network(id) ON DELETE CASCADE
);

-- V57__create_routing_rules.sql
-- Creates the Rules Engine tables and adds trace_data to message_log

CREATE TABLE IF NOT EXISTS routing_rule (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    priority INT NOT NULL DEFAULT 100,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS routing_rule_condition (
    id BIGSERIAL PRIMARY KEY,
    routing_rule_id BIGINT NOT NULL,
    field VARCHAR(50) NOT NULL, -- e.g. SOURCE_ADDRESS, DESTINATION_ADDRESS, MESSAGE_TEXT
    operator VARCHAR(50) NOT NULL, -- e.g. MATCHES_REGEX, EQUALS, CONTAINS
    value TEXT NOT NULL,
    CONSTRAINT fk_rule_condition_rule FOREIGN KEY (routing_rule_id) REFERENCES routing_rule(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS routing_rule_action (
    id BIGSERIAL PRIMARY KEY,
    routing_rule_id BIGINT NOT NULL,
    action_type VARCHAR(50) NOT NULL, -- e.g. REWRITE_SOURCE, REWRITE_TEXT, OVERRIDE_SMSC, FAKE_DLR, DROP
    action_value TEXT,
    CONSTRAINT fk_rule_action_rule FOREIGN KEY (routing_rule_id) REFERENCES routing_rule(id) ON DELETE CASCADE
);

-- Add trace_data to message_log to record rule matches and routing events for the Traces UI
ALTER TABLE message_log ADD COLUMN IF NOT EXISTS trace_data TEXT;

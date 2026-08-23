-- Billing System Schema
-- Idempotent setup for carrier-grade billing

CREATE TABLE IF NOT EXISTS tariff_plan (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tariff_rate (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES tariff_plan(id) ON DELETE CASCADE,
    prefix VARCHAR(20) NOT NULL,
    rate NUMERIC(10, 5) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(plan_id, prefix)
);

CREATE TABLE IF NOT EXISTS client_billing (
    client_id BIGINT PRIMARY KEY REFERENCES smpp_client(id) ON DELETE CASCADE,
    billing_type VARCHAR(20) NOT NULL DEFAULT 'POSTPAID', -- PREPAID or POSTPAID
    balance NUMERIC(15, 5) NOT NULL DEFAULT 0.00000,
    credit_limit NUMERIC(15, 5) NOT NULL DEFAULT 0.00000,
    tariff_plan_id BIGINT REFERENCES tariff_plan(id) ON DELETE SET NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS billing_transaction (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES smpp_client(id) ON DELETE CASCADE,
    amount NUMERIC(15, 5) NOT NULL,
    type VARCHAR(20) NOT NULL, -- TOPUP, ADJUSTMENT
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Insert a default 'Standard EUR' tariff plan if none exists
INSERT INTO tariff_plan (name, currency)
SELECT 'Standard EUR', 'EUR'
WHERE NOT EXISTS (SELECT 1 FROM tariff_plan WHERE name = 'Standard EUR');

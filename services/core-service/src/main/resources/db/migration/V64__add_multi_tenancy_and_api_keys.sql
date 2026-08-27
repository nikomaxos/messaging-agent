-- Create Tenant table
CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Insert a default 'System' tenant for backward compatibility of existing data
INSERT INTO tenants (id, name, status) VALUES (1, 'System Default', 'ACTIVE');

-- Create API Keys table
CREATE TABLE api_keys (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    key_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100),
    roles VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id);

-- Alter existing tables to include tenant_id
-- We default them to 1 (System Default) to avoid null constraints on existing data

ALTER TABLE accounts ADD COLUMN tenant_id BIGINT DEFAULT 1 REFERENCES tenants(id);
CREATE INDEX idx_accounts_tenant ON accounts(tenant_id);

ALTER TABLE devices ADD COLUMN tenant_id BIGINT DEFAULT 1 REFERENCES tenants(id);
CREATE INDEX idx_devices_tenant ON devices(tenant_id);

ALTER TABLE campaigns ADD COLUMN tenant_id BIGINT DEFAULT 1 REFERENCES tenants(id);
CREATE INDEX idx_campaigns_tenant ON campaigns(tenant_id);

ALTER TABLE smsc_suppliers ADD COLUMN tenant_id BIGINT DEFAULT 1 REFERENCES tenants(id);
CREATE INDEX idx_smsc_suppliers_tenant ON smsc_suppliers(tenant_id);

ALTER TABLE message_log ADD COLUMN tenant_id BIGINT DEFAULT 1;
CREATE INDEX idx_message_log_tenant ON message_log(tenant_id);

-- Make tenant_id NOT NULL for strict isolation going forward
-- Note: Doing this in a single transaction after default assignment
ALTER TABLE accounts ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE devices ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE campaigns ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE smsc_suppliers ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE message_log ALTER COLUMN tenant_id SET NOT NULL;

-- V58__extract_usernames.sql
-- Extract production attributes from account into a new username table

CREATE TABLE IF NOT EXISTS username (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    account_id BIGINT NOT NULL,
    whitelisted_ips TEXT,
    enforce_ip_whitelist BOOLEAN NOT NULL DEFAULT false,
    smpp_enabled BOOLEAN NOT NULL DEFAULT true,
    api_enabled BOOLEAN NOT NULL DEFAULT false,
    web_enabled BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_username_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE
);

-- Add username_id to smpp_client
ALTER TABLE smpp_client ADD COLUMN IF NOT EXISTS username_id BIGINT;

-- Create default usernames for existing accounts (primarily customers)
INSERT INTO username (username, account_id, whitelisted_ips, enforce_ip_whitelist, smpp_enabled, api_enabled, web_enabled, created_at, updated_at)
SELECT 
    name, 
    id, 
    whitelisted_ips, 
    enforce_ip_whitelist, 
    smpp_enabled, 
    api_enabled, 
    web_enabled, 
    created_at, 
    updated_at 
FROM account
WHERE type = 'CUSTOMER';

-- Link existing SMPP clients to the newly created usernames
UPDATE smpp_client c
SET username_id = u.id
FROM username u
WHERE c.account_id = u.account_id;

-- Now that data is migrated, add FK constraint and drop old columns
ALTER TABLE smpp_client ADD CONSTRAINT fk_smpp_client_username FOREIGN KEY (username_id) REFERENCES username(id) ON DELETE CASCADE;

-- Drop account_id from smpp_client (since it now belongs to username)
ALTER TABLE smpp_client DROP CONSTRAINT IF EXISTS fk_smpp_client_account;
ALTER TABLE smpp_client DROP COLUMN IF EXISTS account_id;

-- Drop production attributes from account table
ALTER TABLE account DROP COLUMN IF EXISTS whitelisted_ips;
ALTER TABLE account DROP COLUMN IF EXISTS enforce_ip_whitelist;
ALTER TABLE account DROP COLUMN IF EXISTS smpp_enabled;
ALTER TABLE account DROP COLUMN IF EXISTS api_enabled;
ALTER TABLE account DROP COLUMN IF EXISTS web_enabled;

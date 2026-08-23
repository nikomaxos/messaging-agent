-- V56__add_accounts.sql
-- Create the master Account table that consolidates clients, suppliers, and billing

CREATE TABLE IF NOT EXISTS account (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER', -- CUSTOMER, SUPPLIER, BILATERAL
    company_name VARCHAR(150),
    vat_number VARCHAR(50),
    address VARCHAR(255),
    email VARCHAR(150),
    contact_person VARCHAR(100),
    whitelisted_ips TEXT, -- JSON array of IPs or comma-separated
    enforce_ip_whitelist BOOLEAN NOT NULL DEFAULT false,
    smpp_enabled BOOLEAN NOT NULL DEFAULT true,
    api_enabled BOOLEAN NOT NULL DEFAULT false,
    web_enabled BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Add account_id to smpp_client
ALTER TABLE smpp_client ADD COLUMN IF NOT EXISTS account_id BIGINT;

-- Add account_id to smsc_supplier
ALTER TABLE smsc_supplier ADD COLUMN IF NOT EXISTS account_id BIGINT;

-- Create default accounts for existing SMPP Clients
INSERT INTO account (name, type, company_name)
SELECT name, 'CUSTOMER', name 
FROM smpp_client
WHERE account_id IS NULL;

-- Link existing SMPP clients to their newly created accounts
UPDATE smpp_client c
SET account_id = a.id
FROM account a
WHERE c.account_id IS NULL AND c.name = a.name AND a.type = 'CUSTOMER';

-- Create default accounts for existing SMSC Suppliers
INSERT INTO account (name, type, company_name)
SELECT name, 'SUPPLIER', name 
FROM smsc_supplier
WHERE account_id IS NULL;

-- Link existing SMSC Suppliers to their newly created accounts
UPDATE smsc_supplier s
SET account_id = a.id
FROM account a
WHERE s.account_id IS NULL AND s.name = a.name AND a.type = 'SUPPLIER';

-- Add constraints after data is migrated
ALTER TABLE smpp_client ADD CONSTRAINT fk_smpp_client_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE SET NULL;
ALTER TABLE smsc_supplier ADD CONSTRAINT fk_smsc_supplier_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE SET NULL;

-- Migrate client_billing to point to account instead of smpp_client
-- 1. Rename the column client_id to account_id
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'client_billing' AND column_name = 'client_id') THEN
    ALTER TABLE client_billing RENAME COLUMN client_id TO account_id;
  END IF;
END $$;

-- 2. Drop the old foreign key
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'client_billing_client_id_fkey') THEN
    ALTER TABLE client_billing DROP CONSTRAINT client_billing_client_id_fkey;
  END IF;
END $$;

-- 3. Add the new foreign key
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_client_billing_account') THEN
    ALTER TABLE client_billing ADD CONSTRAINT fk_client_billing_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE;
  END IF;
END $$;

-- Migrate billing_transaction
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'billing_transaction' AND column_name = 'client_id') THEN
    ALTER TABLE billing_transaction RENAME COLUMN client_id TO account_id;
  END IF;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'billing_transaction_client_id_fkey') THEN
    ALTER TABLE billing_transaction DROP CONSTRAINT billing_transaction_client_id_fkey;
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_billing_transaction_account') THEN
    ALTER TABLE billing_transaction ADD CONSTRAINT fk_billing_transaction_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE;
  END IF;
END $$;

-- Finally rename tables to match the new architecture
ALTER TABLE IF EXISTS client_billing RENAME TO account_billing;
ALTER TABLE IF EXISTS billing_transaction RENAME TO account_transaction;

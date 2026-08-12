-- Advance SMPP Routing: Add Load Balancer and Fallback controls

-- 1. Add new columns to smpp_routing
ALTER TABLE smpp_routing
ADD COLUMN load_balancer_enabled BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN resend_enabled BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN fallback_smsc_id BIGINT,
ADD COLUMN resend_trigger VARCHAR(20),
ADD COLUMN rcs_expiration_seconds INT;

ALTER TABLE smpp_routing
ADD CONSTRAINT fk_routing_fallback_smsc FOREIGN KEY (fallback_smsc_id) REFERENCES smsc_supplier(id);

-- 2. Create the destination list table (for Load Balancing targets)
CREATE TABLE smpp_routing_destination (
    id BIGSERIAL PRIMARY KEY,
    smpp_routing_id BIGINT NOT NULL,
    device_group_id BIGINT NOT NULL,
    weight_percent INT NOT NULL DEFAULT 100,
    fallback_smsc_id BIGINT,
    
    CONSTRAINT fk_srd_routing FOREIGN KEY (smpp_routing_id) REFERENCES smpp_routing(id) ON DELETE CASCADE,
    CONSTRAINT fk_srd_device_group FOREIGN KEY (device_group_id) REFERENCES device_group(id) ON DELETE CASCADE,
    CONSTRAINT fk_srd_fallback_smsc FOREIGN KEY (fallback_smsc_id) REFERENCES smsc_supplier(id)
);

-- 3. Migrate existing 1:1 data to the new table
INSERT INTO smpp_routing_destination (smpp_routing_id, device_group_id, weight_percent)
SELECT id, device_group_id, 100 FROM smpp_routing;

-- 4. Drop the old column from smpp_routing
ALTER TABLE smpp_routing DROP CONSTRAINT IF EXISTS fki3yeyol2lyh64u3u4gqysf48s; 

-- Alternatively, just DROP COLUMN
ALTER TABLE smpp_routing DROP COLUMN device_group_id CASCADE;

-- 5. Add Expiration tracking to MessageLog
ALTER TABLE message_log
ADD COLUMN rcs_expires_at TIMESTAMP WITH TIME ZONE,
ADD COLUMN fallback_smsc_id BIGINT,
ADD COLUMN resend_trigger VARCHAR(20);

ALTER TABLE message_log
ADD CONSTRAINT fk_ml_fallback_smsc FOREIGN KEY (fallback_smsc_id) REFERENCES smsc_supplier(id);

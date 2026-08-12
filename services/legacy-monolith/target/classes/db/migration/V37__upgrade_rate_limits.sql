ALTER TABLE routing_rate_limits DROP CONSTRAINT idx_rate_limit_unique;

ALTER TABLE routing_rate_limits RENAME COLUMN max_tps TO speed_tps;
ALTER TABLE routing_rate_limits ALTER COLUMN speed_tps TYPE DECIMAL(10, 4);

ALTER TABLE routing_rate_limits ADD COLUMN supplier_id VARCHAR(50) DEFAULT 'ALL';

CREATE UNIQUE INDEX idx_rate_limit_supplier_unique 
ON routing_rate_limits (customer_profile_id, country_code, network_id, supplier_id);

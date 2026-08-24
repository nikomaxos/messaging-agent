-- Create new tree structure for countries and networks
CREATE TABLE country (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    iso_code VARCHAR(5)
);

CREATE TABLE network (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    country_id BIGINT NOT NULL,
    CONSTRAINT fk_network_country FOREIGN KEY (country_id) REFERENCES country(id) ON DELETE CASCADE
);

CREATE TABLE country_mccs (
    country_id BIGINT NOT NULL,
    mcc VARCHAR(255),
    CONSTRAINT fk_country_mcc FOREIGN KEY (country_id) REFERENCES country(id) ON DELETE CASCADE
);

CREATE TABLE network_mncs (
    network_id BIGINT NOT NULL,
    mnc VARCHAR(255),
    CONSTRAINT fk_network_mnc FOREIGN KEY (network_id) REFERENCES network(id) ON DELETE CASCADE
);

-- Update routing rule
ALTER TABLE routing_rule ADD COLUMN enable_routing_per_country_prefix BOOLEAN NOT NULL DEFAULT FALSE;

-- Update smpp routing
ALTER TABLE smpp_routing DROP CONSTRAINT IF EXISTS smpp_routing_country_prefix_id_fkey;
ALTER TABLE smpp_routing DROP COLUMN IF EXISTS country_prefix_id;
ALTER TABLE smpp_routing ADD COLUMN network_id BIGINT;
ALTER TABLE smpp_routing ADD CONSTRAINT fk_smpp_routing_network FOREIGN KEY (network_id) REFERENCES network(id) ON DELETE SET NULL;

-- Drop old country prefixes table
DROP TABLE IF EXISTS country_prefixes;

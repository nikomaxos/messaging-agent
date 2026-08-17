ALTER TABLE smpp_routing ADD COLUMN country_prefix_id BIGINT;
ALTER TABLE smpp_routing ADD CONSTRAINT fk_smpp_routing_country_prefix FOREIGN KEY (country_prefix_id) REFERENCES country_prefixes(id);

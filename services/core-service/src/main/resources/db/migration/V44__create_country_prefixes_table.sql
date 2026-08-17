CREATE TABLE country_prefixes (
    id BIGSERIAL PRIMARY KEY,
    country_name VARCHAR(255) NOT NULL,
    prefix VARCHAR(255) NOT NULL,
    network_name VARCHAR(255) NOT NULL,
    mcc VARCHAR(5),
    mnc VARCHAR(5),
    iso VARCHAR(2),
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_country_prefixes_country_name ON country_prefixes(country_name);
CREATE INDEX idx_country_prefixes_prefix ON country_prefixes(prefix);

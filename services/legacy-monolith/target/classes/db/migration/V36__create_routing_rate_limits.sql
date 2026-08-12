CREATE TABLE routing_rate_limits (
    id SERIAL PRIMARY KEY,
    customer_profile_id VARCHAR(50),
    country_code VARCHAR(10),
    network_id VARCHAR(50),
    max_tps INT NOT NULL DEFAULT 10,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_rate_limit_unique ON routing_rate_limits (customer_profile_id, country_code, network_id);

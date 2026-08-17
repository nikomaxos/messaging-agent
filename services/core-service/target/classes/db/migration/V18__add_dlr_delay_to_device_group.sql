ALTER TABLE device_group ADD COLUMN dlr_delay_min_ms INT NOT NULL DEFAULT 2000;
ALTER TABLE device_group ADD COLUMN dlr_delay_max_ms INT NOT NULL DEFAULT 5000;

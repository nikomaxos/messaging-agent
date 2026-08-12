-- Rename columns from milliseconds to seconds and convert existing values
ALTER TABLE device_group RENAME COLUMN dlr_delay_min_ms TO dlr_delay_min_sec;
ALTER TABLE device_group RENAME COLUMN dlr_delay_max_ms TO dlr_delay_max_sec;
UPDATE device_group SET dlr_delay_min_sec = dlr_delay_min_sec / 1000, dlr_delay_max_sec = dlr_delay_max_sec / 1000;
ALTER TABLE device_group ALTER COLUMN dlr_delay_min_sec SET DEFAULT 2;
ALTER TABLE device_group ALTER COLUMN dlr_delay_max_sec SET DEFAULT 5;

-- V13__add_device_sim_and_charging.sql

ALTER TABLE device
  ALTER COLUMN imei TYPE VARCHAR(100),
  ADD COLUMN is_charging BOOLEAN,
  ADD COLUMN sim_iccid VARCHAR(50),
  ADD COLUMN phone_number VARCHAR(50);

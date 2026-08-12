-- V3__add_device_connected_at.sql
ALTER TABLE device
ADD COLUMN connected_at TIMESTAMP WITH TIME ZONE;

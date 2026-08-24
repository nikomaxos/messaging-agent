-- V63__enhance_countries.sql
ALTER TABLE country ADD COLUMN notes TEXT;
ALTER TABLE country ADD COLUMN quiet_hours_start VARCHAR(10);
ALTER TABLE country ADD COLUMN quiet_hours_end VARCHAR(10);
ALTER TABLE country ADD COLUMN has_dnd_list BOOLEAN DEFAULT FALSE;

ALTER TABLE smpp_routing ADD COLUMN emulate_delivery BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE smpp_routing ADD COLUMN emulated_error_code VARCHAR(20);

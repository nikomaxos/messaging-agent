ALTER TABLE smpp_routing ADD COLUMN fallback_routing_mode VARCHAR(20);
ALTER TABLE smpp_routing ADD COLUMN fallback_device_group_id BIGINT REFERENCES device_group(id);
ALTER TABLE smpp_routing ADD COLUMN fallback_error_codes VARCHAR(255);

ALTER TABLE smpp_routing_destination ADD COLUMN fallback_routing_mode VARCHAR(20);
ALTER TABLE smpp_routing_destination ADD COLUMN fallback_device_group_id BIGINT REFERENCES device_group(id);
ALTER TABLE smpp_routing_destination ADD COLUMN fallback_error_codes VARCHAR(255);

ALTER TABLE message_log ADD COLUMN fallback_routing_mode VARCHAR(20);
ALTER TABLE message_log ADD COLUMN fallback_device_group_id BIGINT REFERENCES device_group(id);
ALTER TABLE message_log ADD COLUMN fallback_error_codes VARCHAR(255);

-- V14: Add device_group_id to message_log for advanced SMPP load-balancing and routing tracking
ALTER TABLE message_log
ADD COLUMN device_group_id BIGINT;

ALTER TABLE message_log
ADD CONSTRAINT fk_ml_device_group FOREIGN KEY (device_group_id) REFERENCES device_group(id);

ALTER TABLE message_log ADD COLUMN smpp_client_id BIGINT;
ALTER TABLE message_log ADD CONSTRAINT fk_message_log_smpp_client FOREIGN KEY (smpp_client_id) REFERENCES smpp_client (id) ON DELETE SET NULL;

-- V5__increase_message_log_text_length.sql
ALTER TABLE message_log ALTER COLUMN message_text TYPE VARCHAR(4000);
ALTER TABLE message_log ALTER COLUMN error_detail TYPE VARCHAR(1000);
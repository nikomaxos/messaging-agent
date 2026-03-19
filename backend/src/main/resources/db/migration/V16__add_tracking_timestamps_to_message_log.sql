ALTER TABLE message_log ADD COLUMN dispatched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE message_log ADD COLUMN rcs_dlr_received_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE message_log ADD COLUMN fallback_dlr_received_at TIMESTAMP WITH TIME ZONE;

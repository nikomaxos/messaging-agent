ALTER TABLE message_log DROP CONSTRAINT IF EXISTS message_log_routing_mode_check;
ALTER TABLE message_log ADD CONSTRAINT message_log_routing_mode_check CHECK (routing_mode::text = ANY (ARRAY['WEBSOCKET'::character varying, 'MATRIX'::character varying, 'SMS'::character varying]::text[]));

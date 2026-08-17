ALTER TABLE smpp_routing DROP CONSTRAINT IF EXISTS smpp_routing_routing_mode_check;
ALTER TABLE smpp_routing ADD CONSTRAINT smpp_routing_routing_mode_check CHECK (routing_mode::text = ANY (ARRAY['WEBSOCKET'::character varying, 'MATRIX'::character varying, 'SMS'::character varying]::text[]));

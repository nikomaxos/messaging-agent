-- V12: Add fallback starting timestamp to message log
ALTER TABLE message_log
ADD COLUMN fallback_started_at TIMESTAMP WITH TIME ZONE;

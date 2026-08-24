-- Add banned flag to usernames to instantly cut off access across all channels
ALTER TABLE username ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;

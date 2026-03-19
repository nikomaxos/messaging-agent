-- Migrate auto_purge from boolean to varchar enum
ALTER TABLE device ALTER COLUMN auto_purge TYPE varchar(20) USING
    CASE WHEN auto_purge = true THEN 'ALL' ELSE 'OFF' END;
ALTER TABLE device ALTER COLUMN auto_purge SET DEFAULT 'OFF';

CREATE TABLE IF NOT EXISTS smsc_supplier_tmp (
  id bigint NOT NULL,
  trigger_resend_error_codes varchar(255) DEFAULT NULL
);

ALTER TABLE smsc_supplier ADD COLUMN trigger_resend_error_codes VARCHAR(255) DEFAULT NULL;

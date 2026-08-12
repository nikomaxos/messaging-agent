ALTER TABLE message_log
ADD COLUMN smsc_supplier_id BIGINT,
ADD CONSTRAINT fk_message_log_smsc_supplier
    FOREIGN KEY (smsc_supplier_id)
    REFERENCES smsc_supplier(id)
    ON DELETE SET NULL;

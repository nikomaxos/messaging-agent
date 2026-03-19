ALTER TABLE message_log ADD COLUMN supplier_message_id VARCHAR(50);
ALTER TABLE message_log ADD COLUMN customer_message_id VARCHAR(50);
ALTER TABLE message_log ADD COLUMN parent_message_id BIGINT REFERENCES message_log(id);

ALTER TABLE smsc_supplier ADD COLUMN sent_count INTEGER DEFAULT 0;

CREATE TABLE device_log (
    id SERIAL PRIMARY KEY,
    device_id BIGINT REFERENCES device(id),
    level VARCHAR(20) NOT NULL,
    event VARCHAR(255) NOT NULL,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

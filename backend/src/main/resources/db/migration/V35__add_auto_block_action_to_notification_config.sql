ALTER TABLE notification_config 
ADD COLUMN auto_block_action VARCHAR(30) DEFAULT 'REJECT_INVDSTADR';

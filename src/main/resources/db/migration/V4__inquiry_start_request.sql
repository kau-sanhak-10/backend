ALTER TABLE inquiry ADD COLUMN start_request_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_inquiry_owner_start_request_hash
    ON inquiry (owner_id, start_request_hash) WHERE start_request_hash IS NOT NULL;

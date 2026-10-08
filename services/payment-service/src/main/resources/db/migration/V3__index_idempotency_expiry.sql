-- The purge job deletes expired records in small batches ordered by expiry;
-- this index turns each batch into a short range scan instead of a full table scan.
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records (expires_at);

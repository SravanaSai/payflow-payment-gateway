ALTER TABLE payments ADD COLUMN capture_attempts INT NOT NULL DEFAULT 0 CHECK (capture_attempts >= 0);

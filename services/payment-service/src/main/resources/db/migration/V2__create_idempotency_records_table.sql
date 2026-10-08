-- Idempotency records for payment-service (database: payment)
-- The primary key is the uniqueness rule INSERT ... ON CONFLICT arbitrates on,
-- so two requests can never both claim the same key in the same scope.
CREATE TABLE idempotency_records (
    merchant_id           UUID         NOT NULL,
    environment           VARCHAR(16)  NOT NULL,
    idempotency_key       VARCHAR(255) NOT NULL,
    request_fingerprint   CHAR(64)     NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    response_status       INT,
    response_content_type VARCHAR(100),
    response_location     VARCHAR(255),
    response_body         BYTEA,
    created_at            TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (merchant_id, environment, idempotency_key),
    CONSTRAINT ck_idempotency_records_environment CHECK (environment IN ('LIVE', 'TEST')),
    CONSTRAINT ck_idempotency_records_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    -- A response is stored exactly when the record is COMPLETED.
    CONSTRAINT ck_idempotency_records_response CHECK ((status = 'COMPLETED') = (response_status IS NOT NULL))
);

-- Payments table for payment-service (database: payment)
-- One currency column covers both amounts, so a capture in a different
-- currency from its authorization cannot be represented at all.
CREATE TABLE payments (
    id                    UUID         PRIMARY KEY,
    merchant_id           UUID         NOT NULL,
    environment           VARCHAR(16)  NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    amount_minor          BIGINT       NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    captured_amount_minor BIGINT       NOT NULL DEFAULT 0,
    description           VARCHAR(500),
    reference             VARCHAR(128),
    failure_reason        VARCHAR(255),
    cancellation_reason   VARCHAR(255),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_payments_environment CHECK (environment IN ('LIVE', 'TEST')),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_payments_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_payments_captured_range CHECK (captured_amount_minor BETWEEN 0 AND amount_minor),
    -- Money has been captured exactly when the payment reached CAPTURED (or was refunded after it).
    CONSTRAINT ck_payments_captured_state CHECK
        ((captured_amount_minor > 0) = (status IN ('CAPTURED', 'REFUNDED')))
);

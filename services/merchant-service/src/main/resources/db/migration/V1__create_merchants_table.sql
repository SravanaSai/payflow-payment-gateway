-- Merchants table for merchant-service (database: merchant)
CREATE TABLE merchants (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP  NOT NULL,
    updated_at TIMESTAMP  NOT NULL
);

CREATE INDEX idx_merchants_email ON merchants (email);

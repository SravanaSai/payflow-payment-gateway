-- API keys for merchant authentication (database: merchant)
-- secret_hash stores HMAC-SHA256(pepper, secret) as base64 — never the plaintext secret.
CREATE TABLE api_keys (
    id             UUID PRIMARY KEY,
    merchant_id    UUID         NOT NULL REFERENCES merchants (id) ON DELETE CASCADE,
    key_id         VARCHAR(64)  NOT NULL,
    environment    VARCHAR(16)  NOT NULL,
    secret_prefix  VARCHAR(16)  NOT NULL,
    secret_hash    VARCHAR(64)  NOT NULL,
    pepper_version INT          NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    expires_at     TIMESTAMP,
    last_used_at   TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uq_api_keys_key_id      UNIQUE (key_id),
    CONSTRAINT uq_api_keys_secret_hash UNIQUE (secret_hash)
);

CREATE INDEX idx_api_keys_merchant ON api_keys (merchant_id);

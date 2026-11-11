ALTER TABLE accounts ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE external_identities (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    verified_email VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_external_identities_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT uq_external_identity_subject UNIQUE (provider, provider_subject),
    CONSTRAINT uq_external_identity_account_provider UNIQUE (account_id, provider)
);

CREATE INDEX idx_external_identities_account ON external_identities (account_id);

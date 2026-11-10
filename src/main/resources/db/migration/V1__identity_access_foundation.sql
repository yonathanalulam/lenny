CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    phone VARCHAR(32),
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    credential_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_accounts_email UNIQUE (email),
    CONSTRAINT uq_accounts_phone UNIQUE (phone),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    CONSTRAINT ck_accounts_credential_version CHECK (credential_version > 0)
);

CREATE TABLE account_roles (
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (account_id, role),
    CONSTRAINT ck_account_roles_role CHECK (role IN ('CUSTOMER', 'SERVICE_PROVIDER', 'ADMIN'))
);

CREATE TABLE marketplace_verifications (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    subject_role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_marketplace_verification_account_role UNIQUE (account_id, subject_role),
    CONSTRAINT ck_marketplace_verification_role CHECK (subject_role IN ('CUSTOMER', 'SERVICE_PROVIDER')),
    CONSTRAINT ck_marketplace_verification_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL,
    credential_version INTEGER NOT NULL,
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_refresh_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_refresh_sessions_account_active
    ON refresh_sessions (account_id, expires_at)
    WHERE revoked_at IS NULL;

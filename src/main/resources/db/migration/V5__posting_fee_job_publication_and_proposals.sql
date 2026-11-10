UPDATE service_requests
SET status = 'OPEN', updated_at = CURRENT_TIMESTAMP
WHERE request_type = 'DIRECT_INVITATION' AND status = 'DRAFT';

ALTER TABLE invitations DROP CONSTRAINT ck_invitation_status;
UPDATE invitations SET status = 'WITHDRAWN' WHERE status = 'CANCELLED';
ALTER TABLE invitations ADD CONSTRAINT ck_invitation_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED'));

CREATE INDEX idx_service_requests_open_job_feed
    ON service_requests (category_id, created_at DESC)
    WHERE request_type = 'JOB_POSTING' AND status = 'OPEN';

CREATE TABLE posting_fee_transactions (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES service_requests(id),
    customer_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_identifier VARCHAR(64) NOT NULL,
    provider_reference_id VARCHAR(255),
    provider_event_id VARCHAR(255),
    idempotency_key UUID NOT NULL,
    failure_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    CONSTRAINT ck_posting_fee_amount CHECK (amount > 0),
    CONSTRAINT ck_posting_fee_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_posting_fee_status CHECK (status IN ('PENDING', 'FAILED', 'SUCCESSFUL')),
    CONSTRAINT ck_posting_fee_verified_state CHECK (
        (status = 'SUCCESSFUL' AND verified_at IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND verified_at IS NULL AND failure_code IS NOT NULL)
        OR (status = 'PENDING' AND verified_at IS NULL AND failure_code IS NULL)
    ),
    CONSTRAINT uq_posting_fee_idempotency UNIQUE (idempotency_key)
);

CREATE UNIQUE INDEX uq_posting_fee_success_per_request
    ON posting_fee_transactions (request_id) WHERE status = 'SUCCESSFUL';
CREATE UNIQUE INDEX uq_posting_fee_provider_reference
    ON posting_fee_transactions (provider_identifier, provider_reference_id)
    WHERE provider_reference_id IS NOT NULL;
CREATE UNIQUE INDEX uq_posting_fee_provider_event
    ON posting_fee_transactions (provider_identifier, provider_event_id)
    WHERE provider_event_id IS NOT NULL;
CREATE INDEX idx_posting_fee_request_status
    ON posting_fee_transactions (request_id, status, created_at DESC);

CREATE TABLE proposals (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES service_requests(id),
    provider_id UUID NOT NULL REFERENCES accounts(id),
    proposed_price NUMERIC(12, 2) NOT NULL,
    expected_timeline VARCHAR(500) NOT NULL,
    provider_message VARCHAR(4000) NOT NULL,
    conditions VARCHAR(2000),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    withdrawn_at TIMESTAMPTZ,
    rejected_at TIMESTAMPTZ,
    CONSTRAINT uq_proposal_request_provider UNIQUE (request_id, provider_id),
    CONSTRAINT ck_proposal_price CHECK (proposed_price > 0),
    CONSTRAINT ck_proposal_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_proposal_terminal_timestamps CHECK (
        (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL AND rejected_at IS NULL)
        OR (status = 'REJECTED' AND rejected_at IS NOT NULL AND withdrawn_at IS NULL)
        OR (status IN ('PENDING', 'ACCEPTED', 'EXPIRED') AND withdrawn_at IS NULL AND rejected_at IS NULL)
    )
);

CREATE INDEX idx_proposals_request_status ON proposals (request_id, status, created_at DESC);
CREATE INDEX idx_proposals_provider ON proposals (provider_id, created_at DESC);

CREATE TABLE marketplace_activity_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_id UUID REFERENCES accounts(id),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_marketplace_activity_aggregate
    ON marketplace_activity_events (aggregate_type, aggregate_id, occurred_at DESC);

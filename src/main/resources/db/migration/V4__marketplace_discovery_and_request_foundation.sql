CREATE TABLE service_categories (
    id UUID PRIMARY KEY,
    parent_id UUID REFERENCES service_categories(id),
    name_en VARCHAR(120) NOT NULL,
    name_am VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_service_category_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE UNIQUE INDEX uq_service_categories_parent_name_en
    ON service_categories (COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::UUID), LOWER(name_en));

CREATE INDEX idx_service_categories_active_parent
    ON service_categories (active, parent_id, name_en);

CREATE TABLE provider_service_categories (
    provider_id UUID NOT NULL REFERENCES provider_profiles(account_id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES service_categories(id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (provider_id, category_id)
);

CREATE INDEX idx_provider_service_categories_category_provider
    ON provider_service_categories (category_id, provider_id);

CREATE TABLE service_requests (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES accounts(id),
    request_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    category_id UUID NOT NULL REFERENCES service_categories(id),
    provider_id UUID REFERENCES accounts(id),
    description VARCHAR(4000) NOT NULL,
    general_area VARCHAR(160) NOT NULL,
    exact_location VARCHAR(1000) NOT NULL,
    preferred_date DATE NOT NULL,
    preferred_start_time TIME,
    preferred_end_time TIME,
    urgency VARCHAR(32),
    budget_min NUMERIC(12, 2),
    budget_max NUMERIC(12, 2),
    special_instructions VARCHAR(4000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT ck_service_request_type CHECK (request_type IN ('DIRECT_INVITATION', 'JOB_POSTING')),
    CONSTRAINT ck_service_request_status CHECK (status IN ('DRAFT', 'AWAITING_POSTING_FEE', 'OPEN', 'PROVIDER_SELECTED', 'CANCELLED')),
    CONSTRAINT ck_service_request_urgency CHECK (urgency IS NULL OR urgency IN ('FLEXIBLE', 'NORMAL', 'URGENT')),
    CONSTRAINT ck_service_request_provider_by_type CHECK (
        (request_type = 'DIRECT_INVITATION' AND provider_id IS NOT NULL)
        OR (request_type = 'JOB_POSTING' AND provider_id IS NULL)
    ),
    CONSTRAINT ck_service_request_time_range CHECK (
        (preferred_start_time IS NULL AND preferred_end_time IS NULL)
        OR (preferred_start_time IS NOT NULL AND preferred_end_time IS NOT NULL AND preferred_start_time < preferred_end_time)
    ),
    CONSTRAINT ck_service_request_budget CHECK (
        (budget_min IS NULL OR budget_min >= 0)
        AND (budget_max IS NULL OR budget_max >= 0)
        AND (budget_min IS NULL OR budget_max IS NULL OR budget_min <= budget_max)
    ),
    CONSTRAINT ck_service_request_cancellation CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)
    )
);

CREATE INDEX idx_service_requests_customer_created
    ON service_requests (customer_id, created_at DESC);

CREATE TABLE request_attachments (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES service_requests(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    protected_object_id UUID NOT NULL REFERENCES protected_objects(id),
    uploaded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_request_attachment_size CHECK (size_bytes > 0)
);

CREATE INDEX idx_request_attachments_request
    ON request_attachments (request_id, uploaded_at);

CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES service_requests(id),
    provider_id UUID NOT NULL REFERENCES accounts(id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_invitations_request_provider UNIQUE (request_id, provider_id),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'CANCELLED'))
);

CREATE INDEX idx_invitations_provider_status
    ON invitations (provider_id, status, created_at DESC);

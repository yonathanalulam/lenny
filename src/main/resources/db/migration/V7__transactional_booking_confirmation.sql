CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES service_requests(id),
    proposal_id UUID NOT NULL REFERENCES proposals(id),
    customer_id UUID NOT NULL REFERENCES accounts(id),
    provider_id UUID NOT NULL REFERENCES accounts(id),
    scheduled_start TIMESTAMPTZ NOT NULL,
    scheduled_end TIMESTAMPTZ NOT NULL,
    agreed_price NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_booking_request UNIQUE (request_id),
    CONSTRAINT uq_booking_proposal UNIQUE (proposal_id),
    CONSTRAINT ck_booking_interval CHECK (scheduled_start < scheduled_end),
    CONSTRAINT ck_booking_price CHECK (agreed_price > 0),
    CONSTRAINT ck_booking_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED'))
);

CREATE INDEX idx_bookings_provider_schedule ON bookings (provider_id, scheduled_start, scheduled_end);
CREATE INDEX idx_bookings_customer_confirmed ON bookings (customer_id, confirmed_at DESC);
CREATE UNIQUE INDEX uq_proposals_accepted_per_request ON proposals (request_id) WHERE status = 'ACCEPTED';

ALTER TABLE service_requests DROP CONSTRAINT ck_service_request_provider_by_type;
ALTER TABLE service_requests ADD CONSTRAINT ck_service_request_provider_by_type CHECK (
    (request_type = 'DIRECT_INVITATION' AND provider_id IS NOT NULL)
    OR (request_type = 'JOB_POSTING' AND status = 'PROVIDER_SELECTED' AND provider_id IS NOT NULL)
    OR (request_type = 'JOB_POSTING' AND status <> 'PROVIDER_SELECTED' AND provider_id IS NULL)
);

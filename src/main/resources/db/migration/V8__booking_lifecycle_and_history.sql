ALTER TABLE bookings
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN provider_reported_complete_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancellation_actor_id UUID REFERENCES accounts(id),
    ADD COLUMN cancellation_reason VARCHAR(500);

ALTER TABLE bookings DROP CONSTRAINT ck_booking_status;
ALTER TABLE bookings ADD CONSTRAINT ck_booking_status CHECK (
    status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETION_PENDING', 'COMPLETED', 'UNDER_REVIEW', 'CANCELLED')
);
ALTER TABLE bookings ADD CONSTRAINT ck_booking_lifecycle_timestamps CHECK (
    (status <> 'IN_PROGRESS' OR started_at IS NOT NULL)
    AND (status <> 'COMPLETION_PENDING' OR (started_at IS NOT NULL AND provider_reported_complete_at IS NOT NULL))
    AND (status <> 'COMPLETED' OR (started_at IS NOT NULL AND provider_reported_complete_at IS NOT NULL AND completed_at IS NOT NULL))
    AND (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancellation_actor_id IS NOT NULL))
);

CREATE TABLE booking_status_history (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_account_id UUID NOT NULL REFERENCES accounts(id),
    actor_context VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500),
    CONSTRAINT ck_booking_history_from_status CHECK (
        from_status IS NULL OR from_status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETION_PENDING', 'COMPLETED', 'UNDER_REVIEW', 'CANCELLED')
    ),
    CONSTRAINT ck_booking_history_to_status CHECK (
        to_status IN ('CONFIRMED', 'IN_PROGRESS', 'COMPLETION_PENDING', 'COMPLETED', 'UNDER_REVIEW', 'CANCELLED')
    ),
    CONSTRAINT ck_booking_history_actor_context CHECK (actor_context IN ('CUSTOMER', 'PROVIDER', 'SYSTEM'))
);

INSERT INTO booking_status_history (id, booking_id, from_status, to_status, actor_account_id, actor_context, occurred_at)
SELECT gen_random_uuid(), id, NULL, 'CONFIRMED', customer_id, 'CUSTOMER', confirmed_at
FROM bookings;

CREATE INDEX idx_booking_history_booking_time ON booking_status_history (booking_id, occurred_at, id);
CREATE INDEX idx_bookings_provider_status_schedule ON bookings (provider_id, status, scheduled_start, scheduled_end);

CREATE TABLE booking_feedback (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    rater_account_id UUID NOT NULL REFERENCES accounts(id),
    ratee_account_id UUID NOT NULL REFERENCES accounts(id),
    direction VARCHAR(32) NOT NULL,
    rating SMALLINT NOT NULL,
    comment VARCHAR(2000),
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    moderation_reason VARCHAR(1000),
    moderated_by UUID REFERENCES accounts(id),
    moderated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_booking_feedback_direction UNIQUE (booking_id, direction),
    CONSTRAINT ck_booking_feedback_direction CHECK (direction IN ('CUSTOMER_TO_PROVIDER', 'PROVIDER_TO_CUSTOMER')),
    CONSTRAINT ck_booking_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_booking_feedback_participants CHECK (rater_account_id <> ratee_account_id),
    CONSTRAINT ck_booking_feedback_moderation CHECK (
        (visible = TRUE AND moderation_reason IS NULL AND moderated_by IS NULL AND moderated_at IS NULL)
        OR (visible = FALSE AND moderation_reason IS NOT NULL AND moderated_by IS NOT NULL AND moderated_at IS NOT NULL)
    )
);

CREATE INDEX idx_booking_feedback_provider_public
    ON booking_feedback (ratee_account_id, created_at DESC, id DESC)
    WHERE direction = 'CUSTOMER_TO_PROVIDER' AND visible = TRUE;
CREATE INDEX idx_booking_feedback_booking ON booking_feedback (booking_id, direction);

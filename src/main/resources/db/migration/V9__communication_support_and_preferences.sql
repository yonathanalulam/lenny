CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    request_id UUID REFERENCES service_requests(id),
    booking_id UUID REFERENCES bookings(id),
    customer_id UUID NOT NULL REFERENCES accounts(id),
    provider_id UUID NOT NULL REFERENCES accounts(id),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_conversation_context CHECK (request_id IS NOT NULL OR booking_id IS NOT NULL),
    CONSTRAINT ck_conversation_participants CHECK (customer_id <> provider_id),
    CONSTRAINT ck_conversation_state CHECK (state IN ('ACTIVE', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_conversations_booking ON conversations (booking_id) WHERE booking_id IS NOT NULL;
CREATE UNIQUE INDEX uq_conversations_request_pair ON conversations (request_id, customer_id, provider_id) WHERE request_id IS NOT NULL;
CREATE INDEX idx_conversations_customer_activity ON conversations (customer_id, last_activity_at DESC, id);
CREATE INDEX idx_conversations_provider_activity ON conversations (provider_id, last_activity_at DESC, id);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    body VARCHAR(4000) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT ck_message_body CHECK (length(btrim(body)) > 0)
);

CREATE INDEX idx_messages_conversation_time ON messages (conversation_id, sent_at DESC, id DESC);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recipient_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    notification_type VARCHAR(80) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(500) NOT NULL,
    related_entity_type VARCHAR(50),
    related_entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    CONSTRAINT ck_notification_relation CHECK (
        (related_entity_type IS NULL AND related_entity_id IS NULL)
        OR (related_entity_type IS NOT NULL AND related_entity_id IS NOT NULL)
    )
);

CREATE INDEX idx_notifications_recipient_unread ON notifications (recipient_account_id, created_at DESC, id DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_recipient_time ON notifications (recipient_account_id, created_at DESC, id DESC);

CREATE TABLE user_blocks (
    blocker_account_id UUID NOT NULL REFERENCES accounts(id),
    blocked_account_id UUID NOT NULL REFERENCES accounts(id),
    reason_category VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_account_id, blocked_account_id),
    CONSTRAINT ck_user_block_self CHECK (blocker_account_id <> blocked_account_id),
    CONSTRAINT ck_user_block_reason CHECK (reason_category IS NULL OR reason_category IN ('HARASSMENT', 'SAFETY', 'SPAM', 'OTHER'))
);

CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_account_id, blocker_account_id);

CREATE TABLE user_reports (
    id UUID PRIMARY KEY,
    reporter_account_id UUID NOT NULL REFERENCES accounts(id),
    reported_account_id UUID NOT NULL REFERENCES accounts(id),
    related_entity_type VARCHAR(32),
    related_entity_id UUID,
    category VARCHAR(32) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES accounts(id),
    moderation_note VARCHAR(1000),
    CONSTRAINT ck_user_report_self CHECK (reporter_account_id <> reported_account_id),
    CONSTRAINT ck_user_report_relation CHECK (
        (related_entity_type IS NULL AND related_entity_id IS NULL)
        OR (related_entity_type IN ('USER', 'REQUEST', 'BOOKING', 'CONVERSATION') AND related_entity_id IS NOT NULL)
    ),
    CONSTRAINT ck_user_report_category CHECK (category IN ('HARASSMENT', 'SAFETY', 'SPAM', 'FRAUD', 'INAPPROPRIATE_CONTENT', 'OTHER')),
    CONSTRAINT ck_user_report_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT ck_user_report_review CHECK (
        (status IN ('OPEN', 'IN_REVIEW') AND reviewed_at IS NULL AND reviewed_by IS NULL)
        OR (status IN ('RESOLVED', 'DISMISSED') AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
    )
);

CREATE INDEX idx_user_reports_status_created ON user_reports (status, created_at DESC, id DESC);
CREATE INDEX idx_user_reports_reporter ON user_reports (reporter_account_id, created_at DESC);

CREATE TABLE support_cases (
    id UUID PRIMARY KEY,
    creator_account_id UUID NOT NULL REFERENCES accounts(id),
    booking_id UUID REFERENCES bookings(id),
    request_id UUID REFERENCES service_requests(id),
    case_type VARCHAR(32) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    assigned_admin_id UUID REFERENCES accounts(id),
    internal_note VARCHAR(2000),
    resolution_outcome VARCHAR(1000),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_support_case_context CHECK (booking_id IS NOT NULL OR request_id IS NOT NULL),
    CONSTRAINT ck_support_case_type CHECK (case_type IN ('SUPPORT', 'BOOKING_DISPUTE')),
    CONSTRAINT ck_support_case_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_support_case_dispute_booking CHECK (case_type <> 'BOOKING_DISPUTE' OR booking_id IS NOT NULL),
    CONSTRAINT ck_support_case_resolution CHECK (
        (status IN ('OPEN', 'IN_REVIEW') AND resolved_at IS NULL)
        OR (status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_support_cases_active_booking_dispute
    ON support_cases (booking_id)
    WHERE case_type = 'BOOKING_DISPUTE' AND status IN ('OPEN', 'IN_REVIEW');
CREATE INDEX idx_support_cases_status_created ON support_cases (status, created_at DESC, id DESC);
CREATE INDEX idx_support_cases_creator ON support_cases (creator_account_id, created_at DESC);

CREATE TABLE account_preferences (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    preferred_language VARCHAR(2) NOT NULL DEFAULT 'EN',
    marketplace_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    communication_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    support_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_preferences_language CHECK (preferred_language IN ('AM', 'EN'))
);

CREATE TABLE platform_audit_events (
    id UUID PRIMARY KEY,
    actor_account_id UUID REFERENCES accounts(id),
    event_type VARCHAR(100) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_platform_audit_time ON platform_audit_events (occurred_at DESC, id DESC);
CREATE INDEX idx_platform_audit_actor_time ON platform_audit_events (actor_account_id, occurred_at DESC);
CREATE INDEX idx_platform_audit_type_time ON platform_audit_events (event_type, occurred_at DESC);

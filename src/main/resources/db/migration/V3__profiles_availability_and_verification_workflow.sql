ALTER TABLE marketplace_verifications
    DROP CONSTRAINT ck_marketplace_verification_role;

UPDATE marketplace_verifications
SET subject_role = 'PROVIDER'
WHERE subject_role = 'SERVICE_PROVIDER';

ALTER TABLE marketplace_verifications
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN reviewer_admin_id UUID REFERENCES accounts(id),
    ADD COLUMN decision_note VARCHAR(2000),
    ADD CONSTRAINT ck_marketplace_verification_role
        CHECK (subject_role IN ('CUSTOMER', 'PROVIDER')),
    ADD CONSTRAINT ck_marketplace_verification_review_state CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewer_admin_id IS NULL AND decision_note IS NULL)
        OR
        (status IN ('APPROVED', 'REJECTED') AND submitted_at IS NOT NULL AND reviewed_at IS NOT NULL
            AND reviewer_admin_id IS NOT NULL AND decision_note IS NOT NULL)
    );

CREATE TABLE protected_objects (
    id UUID PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL,
    created_by UUID NOT NULL REFERENCES accounts(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_protected_objects_storage_key UNIQUE (storage_key)
);

CREATE TABLE customer_profiles (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    service_area VARCHAR(160) NOT NULL,
    preferred_language VARCHAR(32) NOT NULL,
    profile_photo_object_id UUID REFERENCES protected_objects(id),
    setup_complete BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE provider_profiles (
    account_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    professional_description VARCHAR(4000) NOT NULL,
    experience_years INTEGER NOT NULL,
    service_area VARCHAR(160) NOT NULL,
    pricing_summary VARCHAR(1000) NOT NULL,
    portfolio_summary VARCHAR(4000),
    profile_photo_object_id UUID REFERENCES protected_objects(id),
    setup_complete BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_provider_profiles_experience CHECK (experience_years BETWEEN 0 AND 80)
);

CREATE TABLE provider_profile_skills (
    provider_id UUID NOT NULL REFERENCES provider_profiles(account_id) ON DELETE CASCADE,
    skill VARCHAR(100) NOT NULL,
    PRIMARY KEY (provider_id, skill)
);

CREATE TABLE provider_availability (
    provider_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    available BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE provider_availability_ranges (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES provider_availability(provider_id) ON DELETE CASCADE,
    day_of_week VARCHAR(16) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    CONSTRAINT ck_availability_day CHECK (
        day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
    ),
    CONSTRAINT ck_availability_time_range CHECK (start_time < end_time),
    CONSTRAINT uq_availability_exact_range UNIQUE (provider_id, day_of_week, start_time, end_time)
);

CREATE TABLE verification_submissions (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL REFERENCES marketplace_verifications(id) ON DELETE CASCADE,
    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE verification_evidence (
    id UUID PRIMARY KEY,
    submission_id UUID NOT NULL REFERENCES verification_submissions(id) ON DELETE CASCADE,
    evidence_identifier VARCHAR(100) NOT NULL,
    evidence_type VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    protected_object_id UUID NOT NULL REFERENCES protected_objects(id),
    uploaded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_verification_evidence_size CHECK (size_bytes > 0),
    CONSTRAINT uq_verification_evidence_identifier UNIQUE (submission_id, evidence_identifier)
);

CREATE TABLE verification_decisions (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL REFERENCES marketplace_verifications(id) ON DELETE CASCADE,
    resulting_status VARCHAR(32) NOT NULL,
    reviewer_admin_id UUID NOT NULL REFERENCES accounts(id),
    reason VARCHAR(2000) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_verification_decision_status CHECK (resulting_status IN ('APPROVED', 'REJECTED'))
);

CREATE INDEX idx_marketplace_verifications_status
    ON marketplace_verifications (status, submitted_at);

CREATE INDEX idx_verification_submissions_verification
    ON verification_submissions (verification_id, submitted_at DESC);

CREATE INDEX idx_verification_decisions_verification
    ON verification_decisions (verification_id, decided_at DESC);

CREATE INDEX idx_bookings_customer_status_confirmed
    ON bookings (customer_id, status, confirmed_at DESC, id DESC);

CREATE INDEX idx_bookings_provider_confirmed
    ON bookings (provider_id, confirmed_at DESC, id DESC);

CREATE INDEX idx_bookings_provider_status_confirmed
    ON bookings (provider_id, status, confirmed_at DESC, id DESC);

CREATE INDEX idx_service_requests_open_jobs_created
    ON service_requests (created_at DESC, id DESC)
    WHERE request_type = 'JOB_POSTING' AND status = 'OPEN';

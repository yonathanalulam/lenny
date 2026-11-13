ALTER TABLE customer_profiles
    ADD COLUMN full_name VARCHAR(160) NOT NULL DEFAULT '',
    ADD COLUMN service_area_id VARCHAR(100);

UPDATE customer_profiles
SET service_area_id = TRIM(BOTH '-' FROM LOWER(REGEXP_REPLACE(service_area, '[^A-Za-z0-9]+', '-', 'g')));

UPDATE customer_profiles
SET service_area_id = 'legacy-area'
WHERE service_area_id IS NULL OR service_area_id = '';

ALTER TABLE customer_profiles
    ALTER COLUMN full_name DROP DEFAULT,
    ALTER COLUMN service_area_id SET NOT NULL,
    ADD CONSTRAINT ck_customer_profiles_service_area_id
        CHECK (service_area_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

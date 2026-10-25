CREATE TABLE devices (
    id VARCHAR(128) PRIMARY KEY,
    secret_sha256 CHAR(64) NOT NULL,
    firmware_version VARCHAR(64),
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    printer_status VARCHAR(32) NOT NULL DEFAULT 'unknown',
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES print_jobs(id),
    device_id VARCHAR(128) NOT NULL REFERENCES devices(id),
    lease_id UUID NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'leased',
    leased_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message TEXT
);

CREATE INDEX ix_delivery_attempts_job ON delivery_attempts (job_id);
CREATE INDEX ix_delivery_attempts_expiry ON delivery_attempts (status, expires_at);

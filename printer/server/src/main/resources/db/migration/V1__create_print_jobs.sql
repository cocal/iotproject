CREATE TABLE print_jobs (
    id UUID PRIMARY KEY,
    source VARCHAR(32) NOT NULL DEFAULT 'legacy_tcp',
    idempotency_key VARCHAR(255),
    raw_payload TEXT NOT NULL,
    payload_encoding VARCHAR(32) NOT NULL DEFAULT 'GBK',
    parsed_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    template_id VARCHAR(128) NOT NULL DEFAULT 'receipt-xprinter-v1',
    target_device_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'accepted',
    render_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    delivery_status VARCHAR(32) NOT NULL DEFAULT 'pending',
    artifact_path TEXT,
    artifact_data BYTEA,
    artifact_size BIGINT,
    artifact_sha256 CHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rendered_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_print_jobs_source_idempotency
    ON print_jobs (source, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_print_jobs_status_received_at
    ON print_jobs (status, received_at);

CREATE INDEX ix_print_jobs_target_device_status
    ON print_jobs (target_device_id, status);

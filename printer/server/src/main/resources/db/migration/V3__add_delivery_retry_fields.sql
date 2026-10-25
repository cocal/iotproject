ALTER TABLE print_jobs
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 3;

CREATE INDEX ix_print_jobs_lease_retry ON print_jobs (status, attempt_count, updated_at);

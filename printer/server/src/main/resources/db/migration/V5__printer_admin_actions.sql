CREATE TABLE printer_admin_actions (
    id BIGSERIAL PRIMARY KEY,
    operator VARCHAR(128) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_ip INET,
    target_port INTEGER,
    result VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

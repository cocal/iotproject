ALTER TABLE print_jobs ADD COLUMN source_ip INET;
COMMENT ON COLUMN print_jobs.source_ip IS 'TCP peer IP observed by the legacy receiver; historical rows remain NULL';

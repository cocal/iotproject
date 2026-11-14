UPDATE print_jobs
SET status = 'discarded',
    render_status = 'skipped',
    error_code = 'INVALID_FORMAT',
    error_message = '历史记录缺少必需字段或包含无效合计金额',
    updated_at = CURRENT_TIMESTAMP
WHERE source = 'legacy_tcp'
  AND status IN ('accepted', 'queued', 'render_failed')
  AND (
      COALESCE(NULLIF(BTRIM(parsed_payload->>'invoiceNo'), ''), '') = ''
      OR COALESCE(NULLIF(BTRIM(parsed_payload->>'customer'), ''), '') = ''
      OR COALESCE(NULLIF(BTRIM(parsed_payload->>'address'), ''), '') = ''
      OR COALESCE(NULLIF(BTRIM(parsed_payload->>'total'), ''), '') !~ '^[+-]?(([0-9]{1,3}(,[0-9]{3})+|[0-9]+)(\.[0-9]{1,2})?|\.[0-9]{1,2})$'
      OR COALESCE(jsonb_typeof(parsed_payload->'items') <> 'array', TRUE)
      OR jsonb_array_length(CASE WHEN jsonb_typeof(parsed_payload->'items') = 'array'
                                 THEN parsed_payload->'items' ELSE '[]'::jsonb END) = 0
  );

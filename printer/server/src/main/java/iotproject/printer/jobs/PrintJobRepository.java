package iotproject.printer.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Repository
public class PrintJobRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PrintJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID createLegacyJob(String rawPayload, Map<String, Object> parsedPayload, String sourceIp) {
        UUID id = UUID.randomUUID();
        try {
            String json = objectMapper.writeValueAsString(parsedPayload);
            jdbcTemplate.update("""
                    INSERT INTO print_jobs (id, source, raw_payload, payload_encoding, parsed_payload, source_ip)
                    VALUES (?, 'legacy_tcp', ?, 'GBK', ?::jsonb, ?::inet)
                    """, id, rawPayload, json, sourceIp);
            return id;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize parsed print payload", e);
        }
    }

    public UUID createApiJob(String key, String targetDeviceId, String templateId,
                             Map<String, Object> data, ObjectMapper mapper) {
        try {
            String json = mapper.writeValueAsString(data);
            UUID id = UUID.randomUUID();
            var created = jdbcTemplate.query("""
                    INSERT INTO print_jobs (id, source, idempotency_key, raw_payload, payload_encoding,
                        parsed_payload, template_id, target_device_id)
                    VALUES (?, 'http_api', ?, ?, 'UTF-8', ?::jsonb, COALESCE(?, 'receipt-xprinter-v1'), ?)
                    ON CONFLICT (source, idempotency_key) WHERE idempotency_key IS NOT NULL
                    DO NOTHING RETURNING id
                    """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                    id, key, json, json, templateId, targetDeviceId);
            if (created != null) return created;
            ExistingApiJob existing = jdbcTemplate.queryForObject("""
                    SELECT id, raw_payload, template_id, target_device_id FROM print_jobs
                    WHERE source = 'http_api' AND idempotency_key = ?
                    """, (rs, rowNum) -> new ExistingApiJob(rs.getObject("id", UUID.class),
                    rs.getString("raw_payload"), rs.getString("template_id"), rs.getString("target_device_id")), key);
            if (existing == null || !existing.rawPayload().equals(json)
                    || !existing.templateId().equals(templateId == null ? "receipt-xprinter-v1" : templateId)
                    || !java.util.Objects.equals(existing.targetDeviceId(), targetDeviceId)) {
                throw new IllegalStateException("Idempotency key was already used with different print data");
            }
            return existing.id();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize print payload", e);
        }
    }

    private record ExistingApiJob(UUID id, String rawPayload, String templateId, String targetDeviceId) {}

    public void markRendered(UUID id, byte[] artifact, String sha256) {
        jdbcTemplate.update("""
                UPDATE print_jobs
                SET artifact_data = ?, artifact_size = ?, artifact_sha256 = ?,
                    status = 'queued', render_status = 'succeeded', rendered_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'accepted'
                """, artifact, artifact.length, sha256, id);
    }

    public void markRenderFailed(UUID id, String message) {
        jdbcTemplate.update("""
                UPDATE print_jobs
                SET status = 'render_failed', render_status = 'failed', error_code = 'RENDER_FAILED',
                    error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'accepted'
                """, message, id);
    }

    public Optional<PrintJobView> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, source, status, render_status, delivery_status, artifact_size,
                       artifact_sha256, received_at, rendered_at, error_code, error_message, host(source_ip) AS source_ip
                FROM print_jobs WHERE id = ?
                """, (rs, rowNum) -> new PrintJobView(
                rs.getObject("id", UUID.class),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("render_status"),
                rs.getString("delivery_status"),
                rs.getObject("artifact_size", Long.class),
                rs.getString("artifact_sha256"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("rendered_at") == null ? null : rs.getTimestamp("rendered_at").toInstant(),
                rs.getString("error_code"),
                rs.getString("error_message"), rs.getString("source_ip")), id).stream().findFirst();
    }

    public Optional<byte[]> findArtifact(UUID id) {
        return jdbcTemplate.query("SELECT artifact_data FROM print_jobs WHERE id = ? AND artifact_data IS NOT NULL",
                rs -> rs.next() ? Optional.of(rs.getBytes("artifact_data")) : Optional.empty(), id);
    }

    public Optional<byte[]> findArtifactForDevice(UUID id, String deviceId) {
        return jdbcTemplate.query("""
                SELECT j.artifact_data FROM print_jobs j
                WHERE j.id = ? AND j.artifact_data IS NOT NULL
                  AND EXISTS (SELECT 1 FROM delivery_attempts a
                              WHERE a.job_id = j.id AND a.device_id = ?
                                AND a.status IN ('leased', 'received', 'printing'))
                """, rs -> rs.next() ? Optional.of(rs.getBytes("artifact_data")) : Optional.empty(), id, deviceId);
    }

    public record PrintJobView(UUID id, String source, String status, String renderStatus,
                               String deliveryStatus, Long artifactSize, String artifactSha256,
                               java.time.Instant receivedAt, java.time.Instant renderedAt,
                               String errorCode, String errorMessage, String sourceIp) {
    }
}

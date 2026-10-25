package iotproject.printer.device;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeviceTaskRepository {
    private final JdbcTemplate jdbc;

    public DeviceTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<TaskLease> leaseNext(String deviceId, int leaseSeconds) {
        var tasks = jdbc.query("""
                SELECT id, artifact_size, artifact_sha256 FROM print_jobs
                WHERE status = 'queued' AND (target_device_id IS NULL OR target_device_id = ?)
                ORDER BY received_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, rowNum) -> new PendingTask(rs.getObject("id", UUID.class),
                rs.getLong("artifact_size"), rs.getString("artifact_sha256")), deviceId);
        if (tasks.isEmpty()) {
            return Optional.empty();
        }
        PendingTask task = tasks.get(0);
        UUID leaseId = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(leaseSeconds);
        jdbc.update("UPDATE print_jobs SET status = 'leased', delivery_status = 'leased', attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?", task.id());
        jdbc.update("""
                INSERT INTO delivery_attempts (id, job_id, device_id, lease_id, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), task.id(), deviceId, leaseId, expires);
        return Optional.of(new TaskLease(task.id(), leaseId, expires, task.size(), task.sha256()));
    }

    public boolean event(String deviceId, UUID taskId, UUID leaseId, String event, String errorCode, String errorMessage) {
        String attemptStatus = switch (event) {
            case "received" -> "received";
            case "started" -> "printing";
            case "completed" -> "succeeded";
            case "failed" -> "failed";
            default -> null;
        };
        if (attemptStatus == null) {
            return false;
        }
        int updated = jdbc.update("""
                UPDATE delivery_attempts SET status = ?, started_at = CASE WHEN ? = 'printing' THEN CURRENT_TIMESTAMP ELSE started_at END,
                    completed_at = CASE WHEN ? IN ('succeeded', 'failed') THEN CURRENT_TIMESTAMP ELSE completed_at END,
                    error_code = ?, error_message = ?
                WHERE job_id = ? AND device_id = ? AND lease_id = ? AND status NOT IN ('succeeded', 'failed')
                """, attemptStatus, attemptStatus, attemptStatus, errorCode, errorMessage, taskId, deviceId, leaseId);
        if (updated == 0) {
            return jdbc.query("SELECT status FROM delivery_attempts WHERE job_id = ? AND device_id = ? AND lease_id = ?",
                    rs -> rs.next() && ((event.equals("completed") && rs.getString(1).equals("succeeded"))
                            || (event.equals("failed") && rs.getString(1).equals("failed"))), taskId, deviceId, leaseId);
        }
        if (event.equals("failed")) {
            jdbc.update("""
                    UPDATE print_jobs SET
                        status = CASE WHEN attempt_count >= max_attempts THEN 'failed' ELSE 'queued' END,
                        delivery_status = CASE WHEN attempt_count >= max_attempts THEN 'failed' ELSE 'pending' END,
                        error_code = ?, error_message = ?,
                        completed_at = CASE WHEN attempt_count >= max_attempts THEN CURRENT_TIMESTAMP ELSE NULL END,
                        updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, errorCode, errorMessage, taskId);
        } else {
            String jobStatus = event.equals("completed") ? "succeeded" : event.equals("started") ? "printing" : "leased";
            jdbc.update("UPDATE print_jobs SET status = ?, delivery_status = ?, completed_at = CASE WHEN ? = 'succeeded' THEN CURRENT_TIMESTAMP ELSE completed_at END, updated_at = CURRENT_TIMESTAMP WHERE id = ?", jobStatus, attemptStatus, jobStatus, taskId);
        }
        return true;
    }

    @Scheduled(fixedDelayString = "${printer.delivery.reaper-delay-ms:30000}")
    @Transactional
    public void expireLeases() {
        jdbc.update("""
                UPDATE delivery_attempts SET status = 'expired', completed_at = CURRENT_TIMESTAMP,
                    error_code = 'LEASE_EXPIRED', error_message = 'Device lease expired'
                WHERE status IN ('leased', 'received', 'printing') AND expires_at < CURRENT_TIMESTAMP
                """);
        jdbc.update("""
                UPDATE print_jobs j SET status = CASE WHEN j.attempt_count >= j.max_attempts THEN 'failed' ELSE 'queued' END,
                    delivery_status = CASE WHEN j.attempt_count >= j.max_attempts THEN 'failed' ELSE 'pending' END,
                    error_code = CASE WHEN j.attempt_count >= j.max_attempts THEN 'MAX_ATTEMPTS' ELSE NULL END,
                    error_message = CASE WHEN j.attempt_count >= j.max_attempts THEN 'Maximum delivery attempts exceeded' ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE j.status IN ('leased', 'printing')
                  AND NOT EXISTS (SELECT 1 FROM delivery_attempts a WHERE a.job_id = j.id AND a.status IN ('leased', 'received', 'printing'))
                """);
    }

    private record PendingTask(UUID id, long size, String sha256) {}
    public record TaskLease(UUID taskId, UUID leaseId, Instant expiresAt, long size, String sha256) {}
}

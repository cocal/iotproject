package iotproject.printer.device;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import iotproject.printer.jobs.PrintJobRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}")
public class DeviceTaskController {
    private final DeviceAuthService auth;
    private final DeviceTaskRepository tasks;
    private final PrintJobRepository jobs;

    public DeviceTaskController(DeviceAuthService auth, DeviceTaskRepository tasks, PrintJobRepository jobs) {
        this.auth = auth;
        this.tasks = tasks;
        this.jobs = jobs;
    }

    @PostMapping("/poll")
    public ResponseEntity<?> poll(@PathVariable String deviceId, @RequestHeader("X-Device-Key") String key,
                                  @RequestBody(required = false) DeviceHeartbeat heartbeat) {
        if (!auth.authenticate(deviceId, key)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_device_credentials"));
        }
        if (heartbeat != null) {
            auth.heartbeat(deviceId, heartbeat.firmwareVersion(), heartbeat.printerStatus(), heartbeat.capabilitiesJson());
        }
        var task = tasks.leaseNext(deviceId, 120);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("poll_after_ms", 5000);
        response.put("task", task.map(lease -> Map.of(
                "task_id", lease.taskId(), "lease_id", lease.leaseId(), "expires_at", lease.expiresAt(),
                "artifact_url", "/api/v1/devices/" + deviceId + "/tasks/" + lease.taskId() + "/artifact",
                "size", lease.size(), "sha256", lease.sha256(), "format", "escpos")).orElse(null));
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/tasks/{taskId}/artifact", produces = "application/vnd.escpos")
    public ResponseEntity<byte[]> artifact(@PathVariable String deviceId, @PathVariable UUID taskId,
                                           @RequestHeader("X-Device-Key") String key) {
        if (!auth.authenticate(deviceId, key)) {
            return ResponseEntity.status(401).build();
        }
        return jobs.findArtifactForDevice(taskId, deviceId).map(data -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.escpos"))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(data.length)).body(data))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/events")
    public ResponseEntity<?> event(@PathVariable String deviceId, @PathVariable UUID taskId,
                                   @RequestHeader("X-Device-Key") String key, @RequestBody DeviceEvent event) {
        if (!auth.authenticate(deviceId, key)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_device_credentials"));
        }
        boolean accepted = tasks.event(deviceId, taskId, event.leaseId(), event.event(), event.errorCode(), event.errorMessage());
        return accepted ? ResponseEntity.ok(Map.of("accepted", true)) : ResponseEntity.badRequest().body(Map.of("accepted", false, "error", "invalid_lease_or_event"));
    }

    public record DeviceHeartbeat(String firmwareVersion, String printerStatus, String capabilitiesJson) {}
    public record DeviceEvent(UUID leaseId, String event, String errorCode, String errorMessage) {}
}

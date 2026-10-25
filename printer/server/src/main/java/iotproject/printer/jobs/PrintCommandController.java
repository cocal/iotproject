package iotproject.printer.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import iotproject.printer.render.EscPosReceiptRenderer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/print-commands")
public class PrintCommandController {
    private final PrintJobRepository jobs;
    private final EscPosReceiptRenderer renderer;
    private final ObjectMapper mapper;
    private final String apiKey;

    public PrintCommandController(PrintJobRepository jobs, EscPosReceiptRenderer renderer, ObjectMapper mapper,
                                  @org.springframework.beans.factory.annotation.Value("${printer.api.key:}") String apiKey) {
        this.jobs = jobs;
        this.renderer = renderer;
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestHeader(value = "X-API-Key", required = false) String requestApiKey,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                                                       @RequestBody PrintCommand command) {
        if (apiKey.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error", "api_key_not_configured"));
        }
        if (!constantTimeEquals(apiKey, requestApiKey)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_api_key"));
        }
        String key = headerKey == null || headerKey.isBlank() ? command.idempotencyKey() : headerKey;
        if (key == null || key.isBlank() || command.data() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "idempotency_key_and_data_are_required"));
        }
        try {
            UUID id = jobs.createApiJob(key, command.targetDeviceId(), command.templateId(), command.data(), mapper);
            byte[] artifact = renderer.render(command.data());
            jobs.markRendered(id, artifact, sha256(artifact));
            String status = jobs.findById(id).map(PrintJobRepository.PrintJobView::status).orElse("accepted");
            return ResponseEntity.accepted().body(Map.of("job_id", id, "status", status));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", "idempotency_conflict", "message", e.getMessage()));
        }
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return supplied != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record PrintCommand(String idempotencyKey, String templateId, String targetDeviceId,
                               Map<String, Object> data) {
    }
}

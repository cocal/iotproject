package iotproject.printer.device;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class DeviceAuthService {
    private final JdbcTemplate jdbc;

    public DeviceAuthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean authenticate(String deviceId, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        return jdbc.query("SELECT secret_sha256 FROM devices WHERE id = ?", rs -> {
            if (!rs.next()) {
                return false;
            }
            byte[] expected = hex(rs.getString(1));
            byte[] actual = sha256(secret);
            return MessageDigest.isEqual(expected, actual);
        }, deviceId);
    }

    public void heartbeat(String deviceId, String firmwareVersion, String printerStatus, String capabilitiesJson) {
        jdbc.update("""
                UPDATE devices
                SET firmware_version = COALESCE(?, firmware_version),
                    printer_status = COALESCE(?, printer_status),
                    capabilities = COALESCE(?::jsonb, capabilities),
                    last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, firmwareVersion, printerStatus, capabilitiesJson, deviceId);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private byte[] hex(String value) {
        if (value == null || value.length() != 64) {
            return new byte[0];
        }
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}

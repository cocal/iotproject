package iotproject.printer.tunnel;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Supports legacy JSON v1 and the compact binary v2 used by production devices. */
final class WireProtocol {
    private static final int MAGIC = 0x4954;
    private static final Map<Socket, Integer> VERSIONS = Collections.synchronizedMap(new WeakHashMap<>());

    static TunnelFrame read(InputStream input, Socket socket, String authenticatedDevice) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (in.readUnsignedShort() != MAGIC) throw invalid("magic");
        int tag = in.readUnsignedShort();
        int version = tag >>> 8 == 1 ? 1 : tag;
        if ((version != 1 && version != 2) || tag == 1) throw invalid("version");
        int stream = in.readInt();
        int length = in.readInt();
        int flags = in.readInt();
        int type = version == 1 ? tag & 0xff : flags >>> 24;
        if ((version == 1 ? flags : flags & 0x00ffffff) != 0) throw invalid("flags");
        if (length < 0 || length > (type == TunnelFrame.DATA ? TunnelFrame.CHUNK : 512))
            throw invalid("length");
        Integer previous = VERSIONS.get(socket);
        if ((previous != null && previous != version) || (previous == null && type != TunnelFrame.AUTH))
            throw invalid(previous == null ? "first frame" : "version changed");
        byte[] payload = new byte[length];
        in.readFully(payload);
        try {
            TunnelFrame frame = version == 2 ? decode(type, stream, payload, authenticatedDevice)
                    : new TunnelFrame(type, stream, payload);
            if (previous == null) VERSIONS.put(socket, version);
            return frame;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid tunnel frame", e);
        }
    }

    static void write(TunnelFrame frame, OutputStream output, Socket socket) throws IOException {
        Integer version = VERSIONS.get(socket);
        if (version == null) throw invalid("version not set");
        byte[] payload = version == 2 ? encode(frame) : frame.payload();
        ByteBuffer packet = ByteBuffer.allocate(16 + payload.length);
        packet.putShort((short) MAGIC).putShort((short) (version == 1 ? 0x100 | frame.type() : 2));
        packet.putInt(frame.streamId()).putInt(payload.length);
        packet.putInt(version == 1 ? 0 : frame.type() << 24).put(payload);
        output.write(packet.array());
        output.flush();
    }

    private static TunnelFrame decode(int type, int stream, byte[] data, String device) throws IOException {
        TunnelFrame raw = new TunnelFrame(type, stream, data);
        return switch (type) {
            case TunnelFrame.AUTH -> decodeAuth(stream, data);
            case TunnelFrame.PING -> TunnelFrame.json(type, stream, Map.of("nonce", heartbeat(data, device)));
            case TunnelFrame.PONG -> TunnelFrame.json(type, stream, Map.of("nonce", unsigned(data)));
            case TunnelFrame.WINDOW -> TunnelFrame.json(type, stream, Map.of("credit", unsigned(data)));
            case TunnelFrame.OPEN_OK -> TunnelFrame.json(type, stream, Map.of("initial_window", unsigned(data)));
            case TunnelFrame.OPEN_ERROR, TunnelFrame.RESET -> TunnelFrame.json(type, stream, Map.of("code", code(data)));
            case TunnelFrame.DATA, TunnelFrame.FIN, TunnelFrame.CLOSED -> raw;
            default -> throw invalid("client frame type");
        };
    }

    private static TunnelFrame decodeAuth(int stream, byte[] data) throws IOException {
        ByteBuffer body = ByteBuffer.wrap(data);
        String device = readText(body);
        String credential = readText(body);
        String firmware = readText(body);
        if (body.remaining() != 12) throw invalid("AUTH length");
        return TunnelFrame.json(TunnelFrame.AUTH, stream, Map.of("device_id", device, "credential", credential,
                "firmware", firmware, "lan_ip", ipv4(body), "netmask", ipv4(body), "gateway", ipv4(body)));
    }

    private static long heartbeat(byte[] data, String device) throws IOException {
        if (data.length == 4) return unsigned(data);
        if (data.length != 26 || device == null) throw invalid("PING length");
        ByteBuffer body = ByteBuffer.wrap(data);
        long nonce = Integer.toUnsignedLong(body.getInt());
        int free = Short.toUnsignedInt(body.getShort());
        int largest = Short.toUnsignedInt(body.getShort());
        int rssi = body.get();
        int flags = Byte.toUnsignedInt(body.get());
        long uptime = Integer.toUnsignedLong(body.getInt());
        long interval = Integer.toUnsignedLong(body.getInt());
        long tx = Integer.toUnsignedLong(body.getInt());
        long rx = Integer.toUnsignedLong(body.getInt());
        if (largest > free || flags > 1 || interval == 0) throw invalid("PING telemetry");
        System.getLogger(WireProtocol.class.getName()).log(System.Logger.Level.INFO,
                "Tunnel telemetry device={0} free_heap_bytes={1} max_free_block_bytes={2} rssi_dbm={3} active_stream={4} uptime_sec={5} interval_ms={6} tx_bytes={7} rx_bytes={8} tx_bytes_per_sec={9} rx_bytes_per_sec={10}",
                device, free, largest, rssi, flags, uptime, interval, tx, rx, tx * 1000 / interval, rx * 1000 / interval);
        return nonce;
    }

    private static byte[] encode(TunnelFrame frame) throws IOException {
        return switch (frame.type()) {
            case TunnelFrame.AUTH_OK -> {
                JsonNode body = frame.json();
                yield ByteBuffer.allocate(8).putShort(u16(body, "heartbeat_sec"))
                        .putShort(u16(body, "timeout_sec")).putInt(u32(body, "initial_window")).array();
            }
            case TunnelFrame.OPEN -> {
                JsonNode body = frame.json();
                String[] parts = body.path("target_ip").asText().split("\\.", -1);
                if (parts.length != 4) throw invalid("OPEN IPv4");
                ByteBuffer bytes = ByteBuffer.allocate(14);
                for (String part : parts) {
                    try {
                        int octet = Integer.parseInt(part);
                        if (octet < 0 || octet > 255) throw invalid("OPEN IPv4");
                        bytes.put((byte) octet);
                    } catch (NumberFormatException e) { throw invalid("OPEN IPv4"); }
                }
                yield bytes.putShort(u16(body, "target_port")).putInt(u32(body, "initial_window"))
                        .putShort(u16(body, "connect_timeout_ms")).putShort(u16(body, "idle_timeout_sec")).array();
            }
            case TunnelFrame.WINDOW -> packed(frame, "credit");
            case TunnelFrame.PING, TunnelFrame.PONG -> packed(frame, "nonce");
            case TunnelFrame.AUTH_ERROR, TunnelFrame.RESET -> code(
                    frame.json().path("code").asText().getBytes(StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII);
            case TunnelFrame.DATA, TunnelFrame.FIN, TunnelFrame.CLOSED -> frame.payload();
            default -> throw invalid("server frame type");
        };
    }

    private static byte[] packed(TunnelFrame frame, String key) throws IOException {
        return ByteBuffer.allocate(4).putInt(u32(frame.json(), key)).array();
    }
    private static int u32(JsonNode body, String key) throws IOException {
        JsonNode value = body.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > 0xffffffffL)
            throw invalid(key);
        return (int) value.longValue();
    }
    private static short u16(JsonNode body, String key) throws IOException {
        int value = u32(body, key);
        if ((value & 0xffff0000) != 0) throw invalid(key);
        return (short) value;
    }
    private static long unsigned(byte[] data) throws IOException {
        if (data.length != 4) throw invalid("numeric length");
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt());
    }
    private static String code(byte[] data) throws IOException {
        if (data.length == 0 || data.length > 64) throw invalid("error code length");
        for (byte value : data) if (!(value >= 'A' && value <= 'Z' || value >= '0' && value <= '9' || value == '_'))
            throw invalid("error code");
        return new String(data, StandardCharsets.US_ASCII);
    }
    private static String readText(ByteBuffer data) throws IOException {
        if (!data.hasRemaining()) throw invalid("AUTH string length");
        int length = Byte.toUnsignedInt(data.get());
        if (length == 0 || length > data.remaining()) throw invalid("AUTH string length");
        byte[] value = new byte[length];
        data.get(value);
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
        if (text.chars().anyMatch(Character::isISOControl)) throw invalid("AUTH string");
        return text;
    }
    private static String ipv4(ByteBuffer data) {
        return Byte.toUnsignedInt(data.get()) + "." + Byte.toUnsignedInt(data.get()) + "."
                + Byte.toUnsignedInt(data.get()) + "." + Byte.toUnsignedInt(data.get());
    }
    private static IOException invalid(String field) { return new IOException("Invalid tunnel " + field); }
    private WireProtocol() {}
}

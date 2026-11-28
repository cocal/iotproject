package iotproject.printer.tunnel;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static iotproject.printer.tunnel.TunnelFrame.*;
import static org.junit.jupiter.api.Assertions.*;

class WireProtocolTest {
    @Test
    void decodesProductionBinaryAuthAndHeartbeatAndEncodesResponses() throws Exception {
        try (Socket socket = new Socket()) {
            byte[] auth = ByteBuffer.allocate(3 + 15 + 3 + 6 + 12)
                    .put((byte) 15).put("qingliu-esp8266".getBytes(StandardCharsets.UTF_8))
                    .put((byte) 3).put("key".getBytes(StandardCharsets.UTF_8))
                    .put((byte) 6).put("v1.0.8".getBytes(StandardCharsets.UTF_8))
                    .put(new byte[]{(byte) 192, (byte) 168, (byte) 180, (byte) 143,
                            (byte) 255, (byte) 255, (byte) 255, 0,
                            (byte) 192, (byte) 168, (byte) 180, 1}).array();
            TunnelFrame decodedAuth = WireProtocol.read(new ByteArrayInputStream(binaryFrame(AUTH, 0, auth)), socket, null);
            assertEquals("qingliu-esp8266", decodedAuth.json().get("device_id").asText());
            assertEquals("192.168.180.143", decodedAuth.json().get("lan_ip").asText());

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            WireProtocol.write(TunnelFrame.json(AUTH_OK, 0, Map.of("heartbeat_sec", 30,
                    "timeout_sec", 90, "initial_window", WINDOW_SIZE)), response, socket);
            ByteBuffer authOk = ByteBuffer.wrap(response.toByteArray());
            assertEquals(0x4954, Short.toUnsignedInt(authOk.getShort()));
            assertEquals(2, Short.toUnsignedInt(authOk.getShort()));
            assertEquals(0, authOk.getInt());
            assertEquals(8, authOk.getInt());
            assertEquals(AUTH_OK << 24, authOk.getInt());
            assertEquals(30, Short.toUnsignedInt(authOk.getShort()));
            assertEquals(90, Short.toUnsignedInt(authOk.getShort()));
            assertEquals(WINDOW_SIZE, authOk.getInt());

            ByteBuffer ping = ByteBuffer.allocate(26).putInt(0x89abcdef).putShort((short) 7000)
                    .putShort((short) 6500).put((byte) -61).put((byte) 1).putInt(28)
                    .putInt(30000).putInt(1200).putInt(2400);
            TunnelFrame decodedPing = WireProtocol.read(new ByteArrayInputStream(binaryFrame(PING, 0, ping.array())), socket,
                    "qingliu-esp8266");
            assertEquals(0x89abcdefL, decodedPing.json().get("nonce").longValue());

            byte[] openOk = ByteBuffer.allocate(4).putInt(WINDOW_SIZE).array();
            TunnelFrame decodedOpen = WireProtocol.read(new ByteArrayInputStream(binaryFrame(OPEN_OK, 23, openOk)),
                    socket, "qingliu-esp8266");
            assertEquals(23, decodedOpen.streamId());
            assertEquals(WINDOW_SIZE, decodedOpen.json().get("initial_window").intValue());

            response.reset();
            WireProtocol.write(TunnelFrame.json(PONG, 0, Map.of("nonce", 0x89abcdefL)), response, socket);
            assertArrayEquals(new byte[]{'I', 'T', 0, 2, 0, 0, 0, 0, 0, 0, 0, 4,
                    (byte) PONG, 0, 0, 0, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef},
                    response.toByteArray());
        }
    }

    private static byte[] binaryFrame(int type, int stream, byte[] payload) {
        return ByteBuffer.allocate(16 + payload.length).putShort((short) 0x4954).putShort((short) 2)
                .putInt(stream).putInt(payload.length).putInt(type << 24).put(payload).array();
    }
}

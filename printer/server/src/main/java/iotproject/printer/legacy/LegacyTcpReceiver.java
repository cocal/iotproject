package iotproject.printer.legacy;

import iotproject.printer.jobs.PrintJobRepository;
import iotproject.printer.render.EscPosReceiptRenderer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LegacyTcpReceiver {
    private static final Logger log = LoggerFactory.getLogger(LegacyTcpReceiver.class);
    private static final int MAX_LINES = 10_000;
    private static final int MAX_LINE_LENGTH = 16_384;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_CLIENTS = 64;

    private final PrintJobRepository jobs;
    private final LegacyInvoiceParser parser;
    private final EscPosReceiptRenderer renderer;
    private final int port;
    private final String bind;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService clients = Executors.newFixedThreadPool(MAX_CLIENTS, runnable -> {
        Thread thread = new Thread(runnable, "legacy-tcp-client");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore clientSlots = new Semaphore(MAX_CLIENTS);
    private volatile ServerSocket serverSocket;

    public LegacyTcpReceiver(PrintJobRepository jobs, LegacyInvoiceParser parser,
                             EscPosReceiptRenderer renderer,
                             @Value("${printer.legacy.port:9100}") int port,
                             @Value("${printer.legacy.bind:0.0.0.0}") String bind) {
        this.jobs = jobs;
        this.parser = parser;
        this.renderer = renderer;
        this.port = port;
        this.bind = bind;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread acceptor = new Thread(this::acceptConnections, "legacy-tcp-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptConnections() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(bind,port));
            serverSocket = socket;
            log.info("Legacy printer TCP receiver listening on port {}", port);
            while (running.get()) {
                Socket client = socket.accept();
                if (!clientSlots.tryAcquire()) {
                    client.close();
                    continue;
                }
                clients.submit(() -> {
                    try {
                        client.setSoTimeout(READ_TIMEOUT_MS);
                        handle(client);
                    } catch (Exception error) {
                        log.debug("Legacy TCP connection closed: {}", error.getClass().getSimpleName());
                    } finally {
                        clientSlots.release();
                    }
                });
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("Legacy printer TCP receiver stopped", e);
                running.set(false);
            }
        }
    }

    private void handle(Socket socket) {
        List<String> lines = new ArrayList<>();
        boolean submitted = false;
        String sourceIp = socket.getInetAddress().getHostAddress();
        int payloadBytes = 0;
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), Charset.forName("GBK")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH || lines.size() >= MAX_LINES) {
                    log.warn("Rejected oversized legacy print payload from {}", socket.getRemoteSocketAddress());
                    return;
                }
                payloadBytes += line.length() + 1;
                if (payloadBytes > MAX_PAYLOAD_BYTES) {
                    log.warn("Rejected oversized legacy print payload from {}", sourceIp);
                    return;
                }
                lines.add(line);
                if (!submitted && line.contains("地址")) {
                    persist(lines, sourceIp);
                    submitted = true;
                }
            }
            if (!submitted && !lines.isEmpty()) {
                persist(lines, sourceIp);
            }
        } catch (Exception e) {
            log.warn("Could not receive legacy print payload from {}", socket.getRemoteSocketAddress(), e);
            if (!submitted && !lines.isEmpty()) {
                try {
                    persist(lines, sourceIp);
                } catch (Exception persistError) {
                    log.error("Could not persist partial legacy print payload", persistError);
                }
            }
        }
    }

    private void persist(List<String> lines, String sourceIp) {
        String rawPayload = String.join("\n", lines).replace(String.valueOf((char) 0), "\\u0000");
        List<String> databaseSafeLines = lines.stream().map(line -> line.replace((char) 0, '\uFFFD')).toList();
        var parsed = parser.parse(databaseSafeLines);
        var id = jobs.createLegacyJob(rawPayload, parsed, sourceIp);
        @SuppressWarnings("unchecked")
        var validationErrors = (List<String>) parsed.get("validationErrors");
        if (!validationErrors.isEmpty()) {
            jobs.markDiscarded(id, String.join("；", validationErrors));
            log.info("Discarded invalid legacy print job {} from {}: {}", id, sourceIp,
                    String.join(", ", validationErrors));
            return;
        }
        try {
            byte[] artifact = renderer.render(parsed);
            jobs.markRendered(id, artifact, sha256(artifact));
            log.info("Queued legacy print job {} with {} lines and {} bytes", id, lines.size(), artifact.length);
        } catch (RuntimeException e) {
            jobs.markRenderFailed(id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.error("Could not render legacy print job {}", id, e);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            log.debug("Error closing legacy receiver socket", e);
        }
        clients.shutdown();
    }
}

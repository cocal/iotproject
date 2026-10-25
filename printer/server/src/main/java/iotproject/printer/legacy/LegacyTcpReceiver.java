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
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LegacyTcpReceiver {
    private static final Logger log = LoggerFactory.getLogger(LegacyTcpReceiver.class);
    private static final int MAX_LINES = 10_000;
    private static final int MAX_LINE_LENGTH = 16_384;

    private final PrintJobRepository jobs;
    private final LegacyInvoiceParser parser;
    private final EscPosReceiptRenderer renderer;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile ServerSocket serverSocket;

    public LegacyTcpReceiver(PrintJobRepository jobs, LegacyInvoiceParser parser,
                             EscPosReceiptRenderer renderer,
                             @Value("${printer.legacy.port:9100}") int port) {
        this.jobs = jobs;
        this.parser = parser;
        this.renderer = renderer;
        this.port = port;
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
        try (ServerSocket socket = new ServerSocket(port)) {
            serverSocket = socket;
            log.info("Legacy printer TCP receiver listening on port {}", port);
            while (running.get()) {
                Socket client = socket.accept();
                clients.submit(() -> handle(client));
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
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), Charset.forName("GBK")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH || lines.size() >= MAX_LINES) {
                    log.warn("Rejected oversized legacy print payload from {}", socket.getRemoteSocketAddress());
                    return;
                }
                lines.add(line);
                if (!submitted && line.contains("地址")) {
                    persist(lines);
                    submitted = true;
                }
            }
            if (!submitted && !lines.isEmpty()) {
                persist(lines);
            }
        } catch (Exception e) {
            log.warn("Could not receive legacy print payload from {}", socket.getRemoteSocketAddress(), e);
            if (!submitted && !lines.isEmpty()) {
                try {
                    persist(lines);
                } catch (Exception persistError) {
                    log.error("Could not persist partial legacy print payload", persistError);
                }
            }
        }
    }

    private void persist(List<String> lines) {
        String rawPayload = String.join("\n", lines);
        var parsed = parser.parse(lines);
        var id = jobs.createLegacyJob(rawPayload, parsed);
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

package iotproject.printer.render;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class PrinterDriverRenderer {
    private static final long MAX_OUTPUT_BYTES = 2_000_000;
    private final String cupsFilter;
    private final String printerFilter;
    private final String ppd;

    public PrinterDriverRenderer(
            @Value("${printer.render.driver.cupsfilter:/usr/sbin/cupsfilter}") String cupsFilter,
            @Value("${printer.render.driver.filter:/usr/lib/cups/filter/rastertosnailep-xprinter}") String printerFilter,
            @Value("${printer.render.driver.ppd:/usr/share/cups/model/xprinter/XP-80.ppd}") String ppd) {
        this.cupsFilter = cupsFilter;
        this.printerFilter = printerFilter;
        this.ppd = ppd;
    }

    public byte[] render(byte[] png) throws IOException {
        if (png == null || png.length == 0 || png.length > 4_000_000) {
            throw new IllegalArgumentException("driver_input_invalid");
        }
        Path work = Files.createTempDirectory("printer-driver-");
        try {
            Path input = work.resolve("receipt.png");
            Path raster = work.resolve("receipt.raster");
            Path output = work.resolve("receipt.prn");
            Files.write(input, png);
            run(new ProcessBuilder(cupsFilter, "-p", ppd, "-i", "image/png", "-m", "application/vnd.cups-raster",
                    "-o", "Resolution=203dpi", "-o", "PageSize=X72MMY210MM", input.toString()), raster);
            ProcessBuilder driver = new ProcessBuilder(printerFilter, "1", "printer-console", "Receipt", "1", "", raster.toString());
            driver.environment().put("PPD", ppd);
            run(driver, output);
            long size = Files.size(output);
            if (size == 0 || size > MAX_OUTPUT_BYTES) throw new IOException("printer_driver_output_size_invalid");
            return Files.readAllBytes(output);
        } finally {
            try (var files = Files.list(work)) {
                files.forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
            Files.deleteIfExists(work);
        }
    }

    private static void run(ProcessBuilder builder, Path stdout) throws IOException {
        builder.redirectOutput(stdout.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("printer_driver_timeout");
            }
            if (process.exitValue() != 0 || Files.size(stdout) == 0) {
                throw new IOException("printer_driver_failed");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("printer_driver_interrupted", e);
        }
    }
}

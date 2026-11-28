package iotproject.printer.legacy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Cleans boundary noise while preserving the submitted payload for audit and raw reprints. */
@Component
public class LegacyReceiptPreprocessor {
    private final LegacyInvoiceParser parser;

    public LegacyReceiptPreprocessor(LegacyInvoiceParser parser) {
        this.parser = parser;
    }

    public ProcessedReceipt process(String rawPayload) {
        String normalized = rawPayload == null ? "" : rawPayload
                .replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = normalized.lines().map(LegacyReceiptPreprocessor::cleanBoundary)
                .toList();
        while (!lines.isEmpty() && lines.get(0).isBlank()) lines = lines.subList(1, lines.size());
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) lines = lines.subList(0, lines.size() - 1);
        String cleanedText = String.join("\n", lines);
        return new ProcessedReceipt(cleanedText, parser.parse(lines));
    }

    private static String cleanBoundary(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && invisible(line.charAt(start))) start++;
        while (end > start && invisible(line.charAt(end - 1))) end--;
        return line.substring(start, end);
    }

    private static boolean invisible(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch) || Character.isISOControl(ch)
                || Character.getType(ch) == Character.FORMAT;
    }

    public record ProcessedReceipt(String normalizedText, Map<String, Object> payload) {}
}

package iotproject.printer.render;

import javax.imageio.ImageIO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds native ESC/POS output and a browser-independent preview of its device parameters. */
public class NativeEscPosRenderer {
    private static final Charset GBK = Charset.forName("GBK");
    private static final byte ESC = 0x1b;
    private static final byte GS = 0x1d;
    private static final int PRINTABLE_WIDTH_DOTS = 576; // 72 mm at 203 dpi on 80 mm roll paper.
    private static final int FONT_A_ASCII_WIDTH_DOTS = 12;
    private static final int FONT_B_ASCII_WIDTH_DOTS = 9;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(String font, int companyWidth, int companyHeight, int titleWidth, int titleHeight,
                           int bodyWidth, int bodyHeight, int totalWidth, int totalHeight, int lineSpacingDots,
                           int tableSpaces, boolean companyBold, boolean titleBold, boolean bodyBold,
                           boolean totalBold, String companyAlign, String titleAlign, String bodyAlign,
                           String totalAlign, List<LayoutBlock> blocks) {
        public static Settings defaults() {
            return new Settings("A", 1, 1, 2, 1, 1, 1, 1, 1, 16, 3,
                    false, true, false, true, "center", "center", "left", "left", defaultBlocks());
        }

        public Settings validated() {
            boolean legacyLayout = blocks == null || blocks.isEmpty();
            if (!("A".equals(font) || "B".equals(font)) || lineSpacingDots < 0 || lineSpacingDots > 255
                    || tableSpaces < 1 || tableSpaces > 16
                    || legacyLayout && (!align(companyAlign) || !align(titleAlign) || !align(bodyAlign) || !align(totalAlign)
                    || !scale(companyWidth) || !scale(companyHeight) || !scale(titleWidth)
                    || !scale(titleHeight) || !scale(bodyWidth) || !scale(bodyHeight) || !scale(totalWidth)
                    || !scale(totalHeight))) throw new IllegalArgumentException("native_layout_invalid");
            if (blocks != null && (blocks.isEmpty() || blocks.size() > 32 || blocks.stream().anyMatch(block -> block == null || !block.valid())))
                throw new IllegalArgumentException("native_blocks_invalid");
            return this;
        }
        private boolean scale(int value) { return value >= 1 && value <= 8; }
        private boolean align(String value) { return "left".equals(value) || "center".equals(value) || "right".equals(value); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LayoutBlock(String type, String section, String text, int gapDots, int feedDots, int width, int height,
                              boolean bold, String align) {
        private boolean valid() {
            return switch (type == null ? "" : type) {
                case "section" -> List.of("company", "title", "body", "table", "total", "address").contains(section)
                        && gapDots >= 0 && gapDots <= 120 && feedDots == 0 && width >= 1 && width <= 8
                        && height >= 1 && height <= 8 && List.of("left", "center", "right").contains(align);
                case "text" -> text != null && !text.isBlank() && text.length() <= 256 && !text.contains("\n") && !text.contains("\r")
                        && text.chars().noneMatch(ch -> Character.isISOControl(ch))
                        && gapDots >= 0 && gapDots <= 120 && feedDots == 0 && width >= 1 && width <= 8
                        && height >= 1 && height <= 8 && List.of("left", "center", "right").contains(align);
                case "rule" -> gapDots >= 0 && gapDots <= 120 && feedDots == 0;
                case "feed" -> gapDots == 0 && feedDots >= 1 && feedDots <= 120;
                default -> false;
            };
        }
    }

    private record Line(String text, String kind, int gapBeforeDots, int feedDots, LayoutBlock block) {
        private Line(String text, String kind) { this(text, kind, 0, 0, null); }
    }

    private static List<LayoutBlock> defaultBlocks() {
        return List.of(section("company", 1, 1, false, "center"), section("title", 2, 1, true, "center"),
                section("body", 1, 1, false, "left"), rule(), section("table", 1, 1, false, "left"),
                rule(), section("total", 1, 1, true, "left"), section("address", 1, 1, false, "left"));
    }
    private static LayoutBlock section(String name, int width, int height, boolean bold, String align) {
        return new LayoutBlock("section", name, "", 0, 0, width, height, bold, align);
    }
    private static LayoutBlock rule() { return new LayoutBlock("rule", "", "", 0, 0, 1, 1, false, "left"); }

    public byte[] render(String rawText, Settings requested) {
        Settings settings = requested == null ? Settings.defaults() : requested.validated();
        List<Line> lines = format(rawText, settings);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, ESC, '@');
        for (Line line : lines) {
            if (line.gapBeforeDots() > 0) write(out, ESC, 'J', line.gapBeforeDots());
            if ("feed".equals(line.kind())) { write(out, ESC, 'J', line.feedDots()); continue; }
            if ("rule".equals(line.kind())) {
                write(out, ESC, 'a', 0, ESC, 'E', 0, GS, '!', 0, ESC, 'M', fontCode(settings.font()),
                        ESC, '3', Math.min(255, baseHeight(settings) + settings.lineSpacingDots()));
                out.writeBytes("-".repeat("A".equals(settings.font()) ? 48 : 64).getBytes(GBK));
                out.write('\n');
                continue;
            }
            write(out, ESC, 'a', align(alignment(line, settings)));
            write(out, ESC, 'E', bold(line, settings) ? 1 : 0);
            write(out, ESC, 'M', fontCode(settings.font()));
            write(out, GS, '!', ((width(line, settings) - 1) << 4) | (height(line, settings) - 1));
            write(out, ESC, '3', Math.min(255, baseHeight(settings) * height(line, settings) + settings.lineSpacingDots()));
            out.writeBytes(line.text().getBytes(GBK));
            out.write('\n');
        }
        write(out, ESC, '2', ESC, 'a', 0, ESC, 'E', 0, GS, '!', 0, GS, 'V', 66, 0);
        return out.toByteArray();
    }

    public byte[] preview(String rawText, Settings requested) throws IOException {
        Settings settings = requested == null ? Settings.defaults() : requested.validated();
        List<Line> lines = format(rawText, settings);
        int width = PRINTABLE_WIDTH_DOTS;
        int height = 24;
        for (Line line : lines) {
            height += line.gapBeforeDots() + line.feedDots();
            if (!"feed".equals(line.kind())) height += "rule".equals(line.kind()) ? baseHeight(settings) + settings.lineSpacingDots()
                    : baseHeight(settings) * height(line, settings) + settings.lineSpacingDots();
        }
        BufferedImage image = new BufferedImage(width, Math.max(40, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, image.getHeight()); graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y = 12;
            int cellDots = "A".equals(settings.font()) ? FONT_A_ASCII_WIDTH_DOTS : FONT_B_ASCII_WIDTH_DOTS;
            for (Line line : lines) {
                y += line.gapBeforeDots();
                if ("feed".equals(line.kind())) { y += line.feedDots(); continue; }
                if ("rule".equals(line.kind())) {
                    Font font = new Font("Noto Sans CJK SC", Font.PLAIN, baseHeight(settings));
                    graphics.setFont(font);
                    FontMetrics metrics = graphics.getFontMetrics(font);
                    String dashes = "-".repeat(width / cellDots);
                    drawCellText(graphics, metrics, dashes, 0, y + metrics.getAscent(), cellDots, 1, 1);
                    y += baseHeight(settings) + settings.lineSpacingDots();
                    continue;
                }
                int widthScale = width(line, settings), heightScale = height(line, settings);
                Font font = new Font("Noto Sans CJK SC", bold(line, settings) ? Font.BOLD : Font.PLAIN, baseHeight(settings));
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics(font);
                int textWidth = displayWidth(line.text()) * cellDots * widthScale;
                int x = switch (alignment(line, settings)) {
                    case "center" -> Math.max(0, (width - textWidth) / 2);
                    case "right" -> Math.max(0, width - textWidth);
                    default -> 0;
                };
                drawCellText(graphics, metrics, line.text(), x, y + metrics.getAscent() * heightScale,
                        cellDots, widthScale, heightScale);
                y += baseHeight(settings) * heightScale + settings.lineSpacingDots();
            }
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private List<Line> format(String rawText, Settings settings) {
        if (rawText == null || rawText.isBlank()) throw new IllegalArgumentException("source_text_empty");
        if (rawText.length() > 200_000) throw new IllegalArgumentException("source_text_too_large");
        if (settings.blocks() != null && !settings.blocks().isEmpty()) return formatBlocks(rawText, settings);
        String[] source = rawText.replace("\r", "").split("\n");
        int title = -1, company = -1, table = -1;
        for (int i = 0; i < source.length; i++) {
            if (title < 0 && i < 6 && source[i].contains("销售单")) title = i;
            if (table < 0 && source[i].contains("品名") && source[i].contains("数量")) table = i;
        }
        if (title < 0) for (int i = 0; i < Math.min(6, source.length); i++) if (!source[i].isBlank()) { title = i; break; }
        if (title > 0) for (int i = 0; i < title; i++) if (!source[i].isBlank()) { company = i; break; }
        List<Line> result = new ArrayList<>();
        for (int i = 0; i < source.length; i++) {
            String text = source[i].stripTrailing();
            if (text.isBlank()) continue;
            if (text.matches("[-=_]{4,}")) { addRule(result); continue; }
            String kind = text.contains("仓库") ? "ignored" : isAddress(text) ? "address" : i == company ? "company" : i == title ? "title"
                    : text.contains("合计") || text.contains("实收") ? "total"
                    : table >= 0 && i >= table ? "table" : "body";
            if ("ignored".equals(kind)) continue;
            if ((i == table || "total".equals(kind)) && !result.isEmpty()) addRule(result);
            result.add(new Line(trimForAlignment(text, legacyAlignment(kind, settings)), kind));
        }
        List<Line> aligned = new ArrayList<>();
        List<Line> addresses = new ArrayList<>();
        for (int index = 0; index < result.size();) {
            if ("address".equals(result.get(index).kind())) { addresses.add(result.get(index++)); continue; }
            if (!"table".equals(result.get(index).kind())) { aligned.add(result.get(index++)); continue; }
            int end = index;
            List<String> tableRows = new ArrayList<>();
            while (end < result.size() && "table".equals(result.get(end).kind())) tableRows.add(result.get(end++).text());
            formatTableRows(tableRows, settings, settings.bodyWidth()).forEach(text -> aligned.add(new Line(text, "table")));
            index = end;
        }
        aligned.addAll(addresses);
        return aligned;
    }

    private List<Line> formatBlocks(String rawText, Settings settings) {
        String[] source = rawText.replace("\r", "").split("\n");
        int title = -1, table = -1, company = -1;
        for (int i = 0; i < source.length; i++) {
            if (title < 0 && i < 6 && source[i].contains("销售单")) title = i;
            if (table < 0 && source[i].contains("品名") && source[i].contains("数量")) table = i;
        }
        if (title < 0) for (int i = 0; i < Math.min(6, source.length); i++) if (!source[i].isBlank()) { title = i; break; }
        if (title > 0) for (int i = 0; i < title; i++) if (!source[i].isBlank()) { company = i; break; }

        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (int i = 0; i < source.length; i++) {
            String text = source[i].stripTrailing();
            if (text.isBlank() || text.matches("[-=_]{4,}")) continue;
            if (text.contains("仓库")) continue;
            String kind = isAddress(text) ? "address" : i == company ? "company" : i == title ? "title"
                    : text.contains("合计") || text.contains("实收") ? "total"
                    : table >= 0 && i >= table ? "table" : "body";
            sections.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(text);
        }

        List<Line> result = new ArrayList<>();
        for (LayoutBlock block : settings.blocks()) {
            if ("rule".equals(block.type())) {
                if (result.isEmpty() || !"rule".equals(result.get(result.size() - 1).kind()))
                    result.add(new Line("", "rule", block.gapDots(), 0, block));
            } else if ("feed".equals(block.type())) {
                result.add(new Line("", "feed", 0, block.feedDots(), block));
            } else if ("text".equals(block.type())) {
                result.add(new Line(trimForAlignment(block.text(), block.align()), "body", block.gapDots(), 0, block));
            } else {
                List<String> texts = sections.remove(block.section());
                if (texts == null) continue;
                if ("table".equals(block.section())) texts = formatTableRows(texts, settings, block.width());
                for (int index = 0; index < texts.size(); index++) {
                    String text = trimForAlignment(texts.get(index), block.align());
                    result.add(new Line(text, block.section(), index == 0 ? block.gapDots() : 0, 0, block));
                }
            }
        }
        if (!settings.blocks().stream().anyMatch(block -> "section".equals(block.type()) && "address".equals(block.section()))) {
            List<String> addresses = sections.getOrDefault("address", List.of());
            LayoutBlock addressBlock = section("address", 1, 1, false, "left");
            addresses.forEach(text -> result.add(new Line(text, "address", 0, 0, addressBlock)));
        }
        return result;
    }

    private List<String> formatTableRows(List<String> source, Settings settings, int widthScale) {
        int cellDots = "A".equals(settings.font()) ? FONT_A_ASCII_WIDTH_DOTS : FONT_B_ASCII_WIDTH_DOTS;
        int columns = PRINTABLE_WIDTH_DOTS / cellDots / widthScale;
        int gaps = Math.min(settings.tableSpaces(), Math.max(1, columns / 12));
        List<String[]> rows = source.stream().map(this::tableCells).toList();
        if (rows.stream().noneMatch(java.util.Objects::nonNull)) return source;

        int[] widths = new int[4];
        for (String[] row : rows) if (row != null) for (int column = 0; column < 4; column++)
            widths[column] = Math.max(widths[column], displayWidth(row[column]));
        int numericWidth = widths[1] + widths[2] + widths[3];
        if (numericWidth + 3 >= columns) return source;
        int nameWidth = columns - numericWidth - 3 * gaps;
        if (nameWidth < 1) {
            gaps = 1;
            nameWidth = columns - numericWidth - 3;
        }
        if (nameWidth < 1) {
            int excess = 1 - nameWidth;
            for (int column = 3; column >= 1 && excess > 0; column--) {
                int reduction = Math.min(excess, Math.max(0, widths[column] - 1));
                widths[column] -= reduction;
                excess -= reduction;
            }
            nameWidth = Math.max(1, columns - widths[1] - widths[2] - widths[3] - 3 * gaps);
        }
        widths[0] = nameWidth;

        List<String> formatted = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            String[] row = rows.get(rowIndex);
            if (row == null) {
                formatted.addAll(wrapDisplay(source.get(rowIndex), columns));
                continue;
            }
            List<String> nameLines = wrapDisplay(row[0], widths[0]);
            for (int line = 0; line < nameLines.size(); line++) {
                String name = padRight(nameLines.get(line), widths[0]);
                String quantity = line == 0 ? padLeft(row[1], widths[1]) : "";
                String price = line == 0 ? padLeft(row[2], widths[2]) : "";
                String amount = line == 0 ? padLeft(row[3], widths[3]) : "";
                String output = String.join(" ".repeat(gaps), name, quantity, price, amount).stripTrailing();
                if (displayWidth(output) > columns) output = trimDisplay(output, columns);
                formatted.add(output);
            }
        }
        return formatted;
    }

    private void drawCellText(Graphics2D graphics, FontMetrics metrics, String text, int x, int baselineY,
                              int cellDots, int widthScale, int heightScale) {
        int cursorX = x;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String glyph = new String(Character.toChars(codePoint));
            int columns = displayWidth(glyph);
            int glyphWidth = metrics.stringWidth(glyph);
            if (!" ".equals(glyph) && glyphWidth > 0) {
                Graphics2D glyphGraphics = (Graphics2D) graphics.create();
                try {
                    glyphGraphics.translate(cursorX, baselineY);
                    glyphGraphics.scale(cellDots * (double) columns * widthScale / glyphWidth, heightScale);
                    glyphGraphics.drawString(glyph, 0, 0);
                } finally { glyphGraphics.dispose(); }
            }
            cursorX += columns * cellDots * widthScale;
            offset += Character.charCount(codePoint);
        }
    }
    private String[] tableCells(String text) {
        String[] cells = text.trim().split("[\\t ]+");
        if (cells.length < 4) return null;
        return new String[]{String.join(" ", java.util.Arrays.copyOfRange(cells, 0, cells.length - 3)),
                cells[cells.length - 3], cells[cells.length - 2], cells[cells.length - 1]};
    }
    private List<String> wrapDisplay(String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int charWidth = codePoint <= 0xff ? 1 : 2;
            if (width + charWidth > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            if (charWidth <= maxWidth) { line.append(character); width += charWidth; }
            offset += Character.charCount(codePoint);
        }
        if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }
    private String padRight(String value, int width) { return value + " ".repeat(Math.max(0, width - displayWidth(value))); }
    private String padLeft(String value, int width) { return " ".repeat(Math.max(0, width - displayWidth(value))) + value; }
    private String trimDisplay(String value, int maxWidth) {
        StringBuilder result = new StringBuilder();
        int width = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset), charWidth = cp <= 0xff ? 1 : 2;
            if (width + charWidth > maxWidth) break;
            result.appendCodePoint(cp); width += charWidth; offset += Character.charCount(cp);
        }
        return result.toString().stripTrailing();
    }
    private int displayWidth(String value) { return value.codePoints().map(cp -> cp <= 0xff ? 1 : 2).sum(); }
    private void addRule(List<Line> lines) { if (!lines.isEmpty() && !"rule".equals(lines.get(lines.size() - 1).kind())) lines.add(new Line("", "rule")); }
    private int baseHeight(Settings s) { return "A".equals(s.font()) ? 24 : 17; }
    private int fontCode(String font) { return "A".equals(font) ? 0 : 1; }
    private int align(String value) { return "center".equals(value) ? 1 : "right".equals(value) ? 2 : 0; }
    private String trimForAlignment(String text, String alignment) {
        return "left".equals(alignment) ? text : text.stripLeading();
    }
    private String legacyAlignment(String kind, Settings settings) {
        return switch (kind) {
            case "company" -> settings.companyAlign();
            case "title" -> settings.titleAlign();
            case "total" -> settings.totalAlign();
            default -> settings.bodyAlign();
        };
    }
    private boolean isAddress(String text) { return text.contains("地址") || text.toLowerCase().contains("address"); }
    private int width(Line line, Settings s) { if (line.block() != null) return line.block().width(); return switch (line.kind()) { case "company" -> s.companyWidth(); case "title" -> s.titleWidth(); case "total" -> s.totalWidth(); default -> s.bodyWidth(); }; }
    private int height(Line line, Settings s) { if (line.block() != null) return line.block().height(); return switch (line.kind()) { case "company" -> s.companyHeight(); case "title" -> s.titleHeight(); case "total" -> s.totalHeight(); default -> s.bodyHeight(); }; }
    private boolean bold(Line line, Settings s) { if (line.block() != null) return line.block().bold(); return switch (line.kind()) { case "company" -> s.companyBold(); case "title" -> s.titleBold(); case "total" -> s.totalBold(); default -> s.bodyBold(); }; }
    private String alignment(Line line, Settings s) { if (line.block() != null) return line.block().align(); return switch (line.kind()) { case "company" -> s.companyAlign(); case "title" -> s.titleAlign(); case "total" -> s.totalAlign(); default -> s.bodyAlign(); }; }
    private void write(ByteArrayOutputStream out, int... bytes) { for (int value : bytes) out.write(value); }
}

package iotproject.printer.render;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class LegacyEscPosRenderer {
    private static final Charset GBK = Charset.forName("GBK");
    private static final int MAX_WIDTH = 576;
    private static final int MAX_HEIGHT = 4096;
    private static final int RECEIPT_WIDTH = 576;
    private static final DateTimeFormatter PRINT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public record LayoutSettings(int companySize, int titleSize, int bodySize, int totalSize,
                                 int tableSpaces, int lineSpacing, boolean companyBold, boolean titleBold,
                                 boolean bodyBold, boolean totalBold, String companyAlign, String titleAlign,
                                 String bodyAlign, String totalAlign) {
        public static LayoutSettings defaults() {
            return new LayoutSettings(30, 38, 26, 30, 10, 4, false, true, false, false,
                    "center", "center", "left", "left");
        }

        public LayoutSettings validated() {
            if (companySize < 18 || companySize > 48 || titleSize < 18 || titleSize > 56
                    || bodySize < 14 || bodySize > 36 || totalSize < 14 || totalSize > 48
                    || tableSpaces < 1 || tableSpaces > 20 || lineSpacing < 0 || lineSpacing > 20
                    || !validAlign(companyAlign) || !validAlign(titleAlign)
                    || !validAlign(bodyAlign) || !validAlign(totalAlign)) {
                throw new IllegalArgumentException("layout_setting_invalid");
            }
            return this;
        }

        private static boolean validAlign(String value) {
            return "left".equals(value) || "center".equals(value) || "right".equals(value);
        }
    }

    public byte[] renderReceiptPreview(String rawText) throws IOException {
        return renderReceiptPreview(rawText, null);
    }

    public byte[] renderReceiptPreview(String rawText, LayoutSettings settings) throws IOException {
        return renderReceiptPreview(rawText, settings, true);
    }

    public byte[] renderReceiptPreview(String rawText, LayoutSettings settings, boolean includePrintTime) throws IOException {
        return encodePng(renderReceiptImage(rawText, settings, includePrintTime));
    }

    public byte[] renderReceipt(String rawText) throws IOException {
        return renderReceipt(rawText, null);
    }

    public byte[] renderReceipt(String rawText, LayoutSettings settings) throws IOException {
        return renderReceipt(rawText, settings, true);
    }

    public byte[] renderReceipt(String rawText, LayoutSettings settings, boolean includePrintTime) throws IOException {
        return encodeRaster(renderReceiptImage(rawText, settings, includePrintTime));
    }

    private BufferedImage renderReceiptImage(String rawText, LayoutSettings requestedSettings, boolean includePrintTime) {
        if (rawText == null || rawText.isBlank()) throw new IllegalArgumentException("source_text_empty");
        if (rawText.length() > 200_000) throw new IllegalArgumentException("source_text_too_large");
        LayoutSettings settings = requestedSettings == null ? LayoutSettings.defaults() : requestedSettings.validated();
        String[] source = rawText.split("\\r?\\n", -1);
        List<Row> rows = new ArrayList<>();
        int titleIndex = -1;
        int companyIndex = -1;
        Set<Integer> emphasizedIndexes = new TreeSet<>();
        Set<Integer> tableRowIndexes = new TreeSet<>();
        boolean saleTitleFound = false;
        int tableHeaderIndex = -1;
        for (int i = 0; i < source.length; i++) {
            if (i < 6 && titleIndex < 0 && source[i].contains("销售单")) {
                titleIndex = i;
                saleTitleFound = true;
            }
            if (source[i].contains("实收")) emphasizedIndexes.add(i);
            if (tableHeaderIndex < 0 && source[i].contains("品名") && source[i].contains("数量")) {
                tableHeaderIndex = i;
            }
        }
        if (tableHeaderIndex >= 0) {
            for (int i = tableHeaderIndex; i < source.length; i++) {
                if (i > tableHeaderIndex && (source[i].contains("合计") || source[i].contains("实收")
                        || source[i].contains("地址"))) break;
                if (!source[i].isBlank()) tableRowIndexes.add(i);
            }
        }
        if (titleIndex < 0) {
            for (int i = 0; i < Math.min(6, source.length); i++) {
                if (!source[i].isBlank()) { titleIndex = i; break; }
            }
        }
        if (saleTitleFound) {
            for (int i = 0; i < 6; i++) {
                if (i != titleIndex && !source[i].isBlank()) { companyIndex = i; break; }
            }
        }
        Font regular = font("Noto Sans CJK SC", settings.bodySize(), settings.bodyBold());
        Font tableFont = font("Noto Sans CJK SC", 28, false);
        Font company = font("Noto Sans CJK SC", settings.companySize(), settings.companyBold());
        Font title = font("Noto Sans CJK SC", settings.titleSize(), settings.titleBold());
        Font total = font("Noto Sans CJK SC", settings.totalSize(), settings.totalBold());
        TableLayout tableLayout = formatTableRows(source, tableRowIndexes);
        if (companyIndex >= 0) rows.add(new Row(source[companyIndex].trim(), company, settings.companyAlign(),
                settings.lineSpacing() + 34, 10, 8, false));
        if (titleIndex >= 0) rows.add(new Row(source[titleIndex].trim(), title, settings.titleAlign(),
                settings.lineSpacing() + 38, companyIndex >= 0 ? 2 : 10, 12, false));
        Set<Integer> emphasizedAdded = new TreeSet<>();
        for (int i = 0; i < source.length; i++) {
            if (i == titleIndex || i == companyIndex || source[i].isBlank()) continue;
            if (tableLayout.continuationRows.contains(i)) continue;
            String text = source[i].trim();
            if (text.contains("合计")) continue;
            if (text.contains("未收")) continue;
            if (text.contains("仓库") || text.matches("^操作员\\s*[:：]\\s*$")) continue;
            if (text.matches("[-=_]{4,}")) {
                rows.add(new Row("", regular, "left", 10, 3, 3, true));
                continue;
            }
            TableLine tableLine = tableLayout.rows.get(i);
            if (emphasizedIndexes.contains(i)) {
                rows.add(new Row(text, font("Noto Sans CJK SC", settings.totalSize(), true), settings.totalAlign(),
                        settings.lineSpacing() + 32, 12, 12, false));
                emphasizedAdded.add(i);
            } else {
                boolean address = text.contains("地址") || text.toLowerCase().contains("address");
                boolean metadata = text.matches("^(单号|时间|往来单位|客户|用户名|操作员)\\s*[:：].*");
                Font rowFont = tableLine != null ? tableFont : address
                        ? font("Noto Sans CJK SC", Math.max(10, settings.bodySize() - 6), false)
                        : metadata ? metadataFont(text) : regular;
                rows.add(new Row(text, rowFont, settings.bodyAlign(), settings.lineSpacing() + 26,
                        address ? settings.lineSpacing() + 90 : 3, 3, false, tableLine));
            }
        }
        for (int index : emphasizedIndexes) {
            if (!emphasizedAdded.contains(index)) rows.add(new Row(source[index].trim(), total, settings.totalAlign(),
                    settings.lineSpacing() + 32, 12, 12, true));
        }
        if (includePrintTime) {
            String printTime = "打印单据时间：" + ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(PRINT_TIME_FORMAT);
            rows.add(new Row(printTime, font("Noto Sans CJK SC", 20, false),
                    "left", settings.lineSpacing() + 20, 3, 8, false));
        }
        List<LayoutRow> layout = new ArrayList<>(rows.size());
        int height = 10;
        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D measureGraphics = measureImage.createGraphics();
        try {
            for (Row row : rows) {
                measureGraphics.setFont(row.font);
                TableLine tableLine = row.tableLine;
                List<String> lines = tableLine == null ? wrap(row.text, measureGraphics.getFontMetrics())
                        : List.of(singleLine(tableLine.name, measureGraphics.getFontMetrics(), TABLE_NAME_WIDTH));
                layout.add(new LayoutRow(row, lines, tableLine));
                height += row.before + (row.rule ? 7 : 0) + lines.size() * row.leading + row.after;
                if (height > MAX_HEIGHT) throw new IllegalArgumentException("image_dimensions_invalid");
            }
        } finally {
            measureGraphics.dispose();
        }
        if (height > MAX_HEIGHT) throw new IllegalArgumentException("image_dimensions_invalid");
        BufferedImage image = new BufferedImage(RECEIPT_WIDTH, Math.max(30, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = 8;
            for (LayoutRow item : layout) {
                Row row = item.row;
                y += row.before;
                if (row.rule) {
                    for (int x = 0; x < RECEIPT_WIDTH; x += 7)
                        graphics.drawLine(x, y, Math.min(x + 4, RECEIPT_WIDTH - 1), y);
                    y += 7;
                }
                graphics.setFont(row.font);
                FontMetrics metrics = graphics.getFontMetrics();
                if (item.tableLine != null) {
                    TableLine cells = item.tableLine;
                    for (int lineIndex = 0; lineIndex < item.lines.size(); lineIndex++) {
                        graphics.drawString(item.lines.get(lineIndex), 8, y + row.leading - 3);
                        if (lineIndex == 0) {
                            drawRightAligned(graphics, cells.quantity, QUANTITY_RIGHT, y + row.leading - 3);
                            drawRightAligned(graphics, cells.price, PRICE_RIGHT, y + row.leading - 3);
                            drawRightAligned(graphics, cells.amount, AMOUNT_RIGHT, y + row.leading - 3);
                        }
                        y += row.leading;
                    }
                    y += row.after;
                    continue;
                }
                for (String line : item.lines) {
                    int textWidth = metrics.stringWidth(line);
                    int x = switch (row.align) {
                        case "center" -> Math.max(8, (RECEIPT_WIDTH - textWidth) / 2);
                        case "right" -> Math.max(8, RECEIPT_WIDTH - 8 - textWidth);
                        default -> 8;
                    };
                    graphics.drawString(line, x, y + row.leading - 3);
                    y += row.leading;
                }
                y += row.after;
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private Font metadataFont(String text) {
        if (text.startsWith("单号")) return font("Noto Sans CJK SC", 20, false);
        if (text.startsWith("时间")) return font("Noto Sans CJK SC", 20, false);
        if (text.startsWith("往来单位") || text.startsWith("客户") || text.startsWith("操作员"))
            return font("Noto Sans CJK SC", 22, false);
        return font("Noto Sans CJK SC", 20, false);
    }

    private String singleLine(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth) return text;
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String next = new String(Character.toChars(cp));
            if (metrics.stringWidth(result.toString() + next + "…") > maxWidth) break;
            result.append(next);
            offset += Character.charCount(cp);
        }
        return result + "…";
    }

    private Font font(String family, int size, boolean bold) {
        return new Font(family, bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static final int TABLE_NAME_WIDTH = 390;
    private static final int QUANTITY_RIGHT = 410;
    private static final int PRICE_RIGHT = 470;
    private static final int AMOUNT_RIGHT = 568;

    static TableLayout formatTableRows(String[] source, Set<Integer> rowIndexes) {
        Map<Integer, TableLine> rows = new HashMap<>();
        Set<Integer> continuationRows = new TreeSet<>();
        List<Integer> pendingNameRows = new ArrayList<>();
        List<String> pendingNameParts = new ArrayList<>();
        for (int index : new TreeSet<>(rowIndexes)) {
            String text = source[index].trim();
            String[] cells = text.split("[\\t ]+");
            if (text.contains("品名") && text.contains("数量")) {
                rows.put(index, new TableLine("品名", "数量", "单价", "金额"));
                pendingNameRows.clear();
                pendingNameParts.clear();
                continue;
            }
            if (cells.length == 3 && numeric(cells[0]) && numeric(cells[1]) && numeric(cells[2])
                    && !pendingNameRows.isEmpty()) {
                rows.put(pendingNameRows.get(0), new TableLine(String.join(" ", pendingNameParts),
                        cells[0], cells[1], cells[2]));
                continuationRows.addAll(pendingNameRows.subList(1, pendingNameRows.size()));
                continuationRows.add(index);
                pendingNameRows.clear();
                pendingNameParts.clear();
                continue;
            }
            if (cells.length >= 4 && numeric(cells[cells.length - 3]) && numeric(cells[cells.length - 2])
                    && numeric(cells[cells.length - 1])) {
                String name = String.join(" ", Arrays.copyOfRange(cells, 0, cells.length - 3));
                rows.put(index, new TableLine(name, cells[cells.length - 3], cells[cells.length - 2],
                        cells[cells.length - 1]));
                pendingNameRows.clear();
                pendingNameParts.clear();
                continue;
            }
            pendingNameRows.add(index);
            pendingNameParts.add(text);
        }
        return new TableLayout(rows, continuationRows);
    }

    private static boolean numeric(String value) {
        return value.matches("[+-]?\\d+(?:\\.\\d+)?");
    }

    private static void drawRightAligned(Graphics2D graphics, String text, int right, int baseline) {
        graphics.drawString(text, right - graphics.getFontMetrics().stringWidth(text), baseline);
    }

    static Map<Integer,String> alignTableRows(String[] source, Set<Integer> rowIndexes, int spaces, Font font) {
        if (rowIndexes.isEmpty()) return Map.of();
        BufferedImage metricsImage=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=metricsImage.createGraphics();
        try {
            graphics.setFont(font);
            FontMetrics metrics=graphics.getFontMetrics();
            Map<Integer,List<String>> cellsByRow=new LinkedHashMap<>();
            int[] columnWidths=new int[4];
            for(int index:rowIndexes){
                String[] tokens=source[index].trim().split("[\\t ]+");
                if(tokens.length<4)continue;
                List<String> cells=List.of(String.join(" ",Arrays.copyOfRange(tokens,0,tokens.length-3)),
                        tokens[tokens.length-3],tokens[tokens.length-2],tokens[tokens.length-1]);
                cellsByRow.put(index,cells);
                for(int column=0;column<cells.size();column++)columnWidths[column]=Math.max(columnWidths[column],metrics.stringWidth(cells.get(column)));
            }
            if(cellsByRow.isEmpty())return Map.of();
            int spaceWidth=Math.max(1,metrics.stringWidth(" "));
            int contentWidth=Arrays.stream(columnWidths).sum();
            int maxSpaces=Math.max(1,(560-contentWidth)/(3*spaceWidth));
            int effectiveSpaces=Math.min(spaces,maxSpaces);
            Map<Integer,String> aligned=new HashMap<>();
            for(var entry:cellsByRow.entrySet()){
                StringBuilder row=new StringBuilder();List<String> cells=entry.getValue();
                for(int column=0;column<cells.size();column++){
                    String cell=cells.get(column);row.append(cell);
                    if(column<cells.size()-1){
                        int extra=Math.max(0,columnWidths[column]-metrics.stringWidth(cell));
                        int padding=(int)Math.ceil((double)extra/spaceWidth)+effectiveSpaces;
                        row.append(" ".repeat(Math.max(effectiveSpaces,padding)));
                    }
                }
                aligned.put(entry.getKey(),row.toString());
            }
            return aligned;
        } finally {
            graphics.dispose();
        }
    }

    private List<String> wrap(String text, FontMetrics metrics) {
        return wrap(text, metrics, 560);
    }

    private List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String character = new String(Character.toChars(cp));
            if (part.length() > 0 && metrics.stringWidth(part + character) > maxWidth) {
                lines.add(part.toString());
                part.setLength(0);
            }
            part.append(character);
            offset += Character.charCount(cp);
        }
        lines.add(part.toString());
        return lines;
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return png.toByteArray();
    }

    private record Row(String text, Font font, String align, int leading, int before, int after, boolean rule,
                       TableLine tableLine) {
        private Row(String text, Font font, String align, int leading, int before, int after, boolean rule) {
            this(text, font, align, leading, before, after, rule, null);
        }
    }
    record TableLayout(Map<Integer, TableLine> rows, Set<Integer> continuationRows) {}
    record TableLine(String name, String quantity, String price, String amount) {}
    private record LayoutRow(Row row, List<String> lines, TableLine tableLine) {}

    public byte[] renderOriginal(String rawText) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1b);
        out.write('@');
        String safeText = rawText.replace("\u0000", "")
                .replaceAll("[\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        out.writeBytes(safeText.getBytes(GBK));
        out.write(0x0a);
        out.write(0x1b);
        out.write('d');
        out.write(3);
        out.write(0x1d);
        out.write('V');
        out.write(66);
        out.write(0);
        return out.toByteArray();
    }

    public byte[] renderImage(byte[] pngBytes) throws IOException {
        if (pngBytes == null || pngBytes.length == 0 || pngBytes.length > 1_000_000) {
            throw new IllegalArgumentException("image_size_invalid");
        }
        if (pngBytes.length < 8 || pngBytes[0] != (byte) 0x89 || pngBytes[1] != 'P'
                || pngBytes[2] != 'N' || pngBytes[3] != 'G' || pngBytes[4] != 0x0d
                || pngBytes[5] != 0x0a || pngBytes[6] != 0x1a || pngBytes[7] != 0x0a) {
            throw new IllegalArgumentException("invalid_image_format");
        }
        BufferedImage image;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(pngBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("invalid_image_format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || width > MAX_WIDTH || height < 1 || height > MAX_HEIGHT
                        || (long) width * height > 1_500_000) {
                    throw new IllegalArgumentException("image_dimensions_invalid");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null) {
            throw new IllegalArgumentException("image_dimensions_invalid");
        }

        return encodeRaster(image);
    }

    private byte[] encodeRaster(BufferedImage image) {
        if (image.getWidth() < 1 || image.getWidth() > MAX_WIDTH || image.getHeight() < 1
                || image.getHeight() > MAX_HEIGHT || (long) image.getWidth() * image.getHeight() > 1_500_000) {
            throw new IllegalArgumentException("image_dimensions_invalid");
        }
        int widthBytes = (image.getWidth() + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + widthBytes * image.getHeight());
        out.write(0x1b);
        out.write('@');
        out.write(0x1d);
        out.write('v');
        out.write('0');
        out.write(0);
        out.write(widthBytes & 0xff);
        out.write((widthBytes >>> 8) & 0xff);
        out.write(image.getHeight() & 0xff);
        out.write((image.getHeight() >>> 8) & 0xff);

        for (int y = 0; y < image.getHeight(); y++) {
            for (int byteX = 0; byteX < widthBytes; byteX++) {
                int packed = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = byteX * 8 + bit;
                    if (x < image.getWidth()) {
                        int rgb = image.getRGB(x, y);
                        int luminance = (((rgb >>> 16) & 0xff) * 299
                                + ((rgb >>> 8) & 0xff) * 587
                                + (rgb & 0xff) * 114) / 1000;
                        if (luminance < 192) packed |= 0x80 >>> bit;
                    }
                }
                out.write(packed);
            }
        }
        out.write(0x0a);
        out.write(0x1d);
        out.write('V');
        out.write(66);
        out.write(0);
        return out.toByteArray();
    }
}

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
    public record LayoutSettings(int companySize, int titleSize, int bodySize, int totalSize,
                                 int tableSpaces, int lineSpacing, boolean companyBold, boolean titleBold,
                                 boolean bodyBold, boolean totalBold, String companyAlign, String titleAlign,
                                 String bodyAlign, String totalAlign) {
        public static LayoutSettings defaults() {
            return new LayoutSettings(28, 34, 20, 24, 5, 2, false, true, false, false,
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
        return encodePng(renderReceiptImage(rawText, settings));
    }

    public byte[] renderReceipt(String rawText) throws IOException {
        return renderReceipt(rawText, null);
    }

    public byte[] renderReceipt(String rawText, LayoutSettings settings) throws IOException {
        return encodeRaster(renderReceiptImage(rawText, settings));
    }

    private BufferedImage renderReceiptImage(String rawText, LayoutSettings requestedSettings) {
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
            if (source[i].contains("合计") || source[i].contains("实收")) emphasizedIndexes.add(i);
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
        Font company = font("Noto Sans CJK SC", settings.companySize(), settings.companyBold());
        Font title = font("Noto Sans CJK SC", settings.titleSize(), settings.titleBold());
        Font total = font("Noto Sans CJK SC", settings.totalSize(), settings.totalBold());
        Map<Integer,String> formattedTableRows=alignTableRows(source,tableRowIndexes,settings.tableSpaces(),regular);
        if (companyIndex >= 0) rows.add(new Row(source[companyIndex].trim(), company, settings.companyAlign(),
                settings.lineSpacing() + 34, 10, 8, false));
        if (titleIndex >= 0) rows.add(new Row(source[titleIndex].trim(), title, settings.titleAlign(),
                settings.lineSpacing() + 38, companyIndex >= 0 ? 2 : 10, 12, false));
        Set<Integer> emphasizedAdded = new TreeSet<>();
        for (int i = 0; i < source.length; i++) {
            boolean included = i < 6 || (i >= 8 && i < source.length - 4);
            if (!included || i == titleIndex || i == companyIndex || source[i].isBlank()) continue;
            String text = source[i].trim();
            if (formattedTableRows.containsKey(i)) text = formattedTableRows.get(i);
            if (emphasizedIndexes.contains(i)) {
                rows.add(new Row(text, total, settings.totalAlign(), settings.lineSpacing() + 32, 12, 12, true));
                emphasizedAdded.add(i);
            } else {
                rows.add(new Row(text, regular, settings.bodyAlign(), settings.lineSpacing() + 26, 3, 3, false));
            }
        }
        for (int index : emphasizedIndexes) {
            if (!emphasizedAdded.contains(index)) rows.add(new Row(source[index].trim(), total, settings.totalAlign(),
                    settings.lineSpacing() + 32, 12, 12, true));
        }
        List<LayoutRow> layout = new ArrayList<>(rows.size());
        int height = 10;
        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D measureGraphics = measureImage.createGraphics();
        try {
            for (Row row : rows) {
                measureGraphics.setFont(row.font);
                List<String> lines = wrap(row.text, measureGraphics.getFontMetrics());
                layout.add(new LayoutRow(row, lines));
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
                if (row.rule) { graphics.fillRect(8, y, 560, 1); y += 7; }
                graphics.setFont(row.font);
                FontMetrics metrics = graphics.getFontMetrics();
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

    private Font font(String family, int size, boolean bold) {
        return new Font(family, bold ? Font.BOLD : Font.PLAIN, size);
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
        List<String> lines = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String character = new String(Character.toChars(cp));
            if (part.length() > 0 && metrics.stringWidth(part + character) > 560) {
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

    private record Row(String text, Font font, String align, int leading, int before, int after, boolean rule) {}
    private record LayoutRow(Row row, List<String> lines) {}

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

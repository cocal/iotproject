package iotproject.printer.render;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LegacyEscPosRendererTest {
    private final LegacyEscPosRenderer renderer = new LegacyEscPosRenderer();

    @Test
    void originalModePrintsStoredTextAsGbkWithoutEmbeddedControlCommands() {
        byte[] output = renderer.renderOriginal("客户：张三\n地址：上海\u0000");
        byte[] expectedText = "客户：张三\n地址：上海".getBytes(Charset.forName("GBK"));

        assertArrayEquals(new byte[]{0x1b, '@'}, new byte[]{output[0], output[1]});
        assertTrue(indexOf(output, expectedText) >= 2);
        assertEquals(0x1d, output[output.length - 4]);
        assertEquals('V', output[output.length - 3]);
    }

    @Test
    void imageModeConvertsBlackPixelsIntoEscPosRasterRows() throws Exception {
        BufferedImage image = new BufferedImage(8, 1, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) image.setRGB(x, 0, 0xffffff);
        image.setRGB(0, 0, 0x000000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        byte[] output = renderer.renderImage(png.toByteArray());

        assertArrayEquals(new byte[]{0x1b, '@', 0x1d, 'v', '0', 0, 1, 0, 1, 0},
                java.util.Arrays.copyOf(output, 10));
        assertEquals((byte) 0x80, output[10]);
        assertEquals(0x1d, output[output.length - 4]);
        assertEquals('V', output[output.length - 3]);
    }

    @Test
    void rejectsImagesOutsidePrinterBounds() throws Exception {
        BufferedImage image = new BufferedImage(577, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> renderer.renderImage(png.toByteArray()));
        assertEquals("image_dimensions_invalid", error.getMessage());
    }

    @Test
    void rendersPreviewAndPrintableRasterFromStoredReceiptText() throws Exception {
        byte[] preview = renderer.renderReceiptPreview("清流镇同利五金电器销售部\n销售单\n商品  数量\n合计金额  12.00", null, false);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(preview));
        byte[] printable = renderer.renderReceipt("清流镇同利五金电器销售部\n销售单\n商品  数量\n合计金额  12.00", null, false);

        assertNotNull(image);
        assertEquals(576, image.getWidth());
        assertTrue(image.getHeight() >= 120);
        assertArrayEquals(new byte[]{0x1b, '@', 0x1d, 'v', '0', 0, 72, 0},
                java.util.Arrays.copyOf(printable, 8));
        assertArrayEquals(printable, renderer.renderImage(preview));
    }

    @Test
    void keepsFirstItemAndTrailingAddressInReceiptPreview() throws Exception {
        String receipt = "清流镇同利五金电器销售部\n销售单\n单号：XS-001\n时间：2026-11-15\n往来单位：刘兴全"
                + "\n操作员：\n仓库：一号仓\n--------------------\n品名 数量 单价 金额"
                + "\n第一件商品 1 2.00 2.00\n第二件商品 1 3.00 3.00"
                + "\n合计金额：5.00\n实收：5.00\n地址：清流镇测试街道";
        String withoutExpectedRows = receipt.replace("品名 数量 单价 金额", "")
                .replace("第一件商品 1 2.00 2.00", "").replace("地址：清流镇测试街道", "");

        assertFalse(java.util.Arrays.equals(renderer.renderReceiptPreview(receipt),
                renderer.renderReceiptPreview(withoutExpectedRows)));
        assertArrayEquals(renderer.renderReceiptPreview(receipt.replace("仓库：一号仓\n", "")
                        .replace("操作员：\n", "")),
                renderer.renderReceiptPreview(receipt));
    }

    @Test
    void removesTotalKeepsReceivedAndAddsSmallerSpacedAddressInReceiptRender() throws Exception {
        String receipt = "店名\n销售单\n品名 数量 单价 金额\n螺丝 1 2.00 2.00\n"
                + "合计金额：2.00\n实收金额：2.00\n地址：清流镇测试街道";
        String withoutTotal = receipt.replace("合计金额：2.00\n", "");
        BufferedImage withAddress = ImageIO.read(new java.io.ByteArrayInputStream(renderer.renderReceiptPreview(receipt)));
        BufferedImage withoutAddress = ImageIO.read(new java.io.ByteArrayInputStream(
                renderer.renderReceiptPreview(withoutTotal.replace("地址：清流镇测试街道", ""))));

        assertArrayEquals(renderer.renderReceiptPreview(withoutTotal), renderer.renderReceiptPreview(receipt));
        assertTrue(withAddress.getHeight() > withoutAddress.getHeight() + 80);
    }

    @Test
    void drawsDashedReceiptRulesAcrossFullPaperWidth() throws Exception {
        String receipt = "店名\n销售单\n--------------------\n品名 数量 单价 金额\n商品 1 2.00 2.00\n"
                + "--------------------\n实收：2.00\n地址：测试地址";
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(renderer.renderReceiptPreview(receipt)));

        boolean fullWidthDashedRow = false;
        for (int y = 0; y < image.getHeight(); y++) {
            if (image.getRGB(0, y) == 0xff000000 && image.getRGB(image.getWidth() - 1, y) == 0xff000000) {
                boolean hasGap = false;
                for (int x = 1; x < image.getWidth() - 1; x++) {
                    if (image.getRGB(x, y) == 0xffffffff) { hasGap = true; break; }
                }
                if (hasGap) { fullWidthDashedRow = true; break; }
            }
        }
        assertTrue(fullWidthDashedRow, "separator should be dashed and reach both paper edges");
    }

    @Test
    void spreadsTableColumnsAcrossReceiptWithoutWrappingAmounts() {
        String[] source = {"品名 数量 单价 金额", "公牛一开双(个) 3 9.30 27.90",
                "20多联B管(根) 4 2.56 10.24", "明线盒(个) 2 0.86 1.72"};
        Font tableFont = new Font("Noto Sans CJK SC", Font.PLAIN, 27);
        Map<Integer, String> rows = LegacyEscPosRenderer.alignTableRows(source, Set.of(0, 1, 2, 3), 10, tableFont);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(tableFont);
            FontMetrics metrics = graphics.getFontMetrics();
            int widestRow = rows.values().stream().mapToInt(metrics::stringWidth).max().orElseThrow();
            assertTrue(widestRow > 500, "table columns should use most of the printable width");
            assertTrue(widestRow <= 560, "amount column should remain on the same line");
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void wrapsOnlyLongProductNamesAndKeepsNumericColumnsOnFirstLine() throws Exception {
        String receipt = "清流镇同利五金电器销售部\n销售单\n单号：LONG-001\n时间：2026-11-22"
                + "\n品名 数量 单价 金额"
                + "\n普通插座 2 18.50 37.00"
                + "\n公牛超薄墙壁开关插座面板家用86型一开双控带荧光大板白色 3 26.80 80.40"
                + "\nPVC给水管热熔管材四分管道抗压耐高温饮用水管(每根4米) 1 105.00 105.00"
                + "\n加厚不锈钢十字槽沉头自攻螺丝木螺钉装修固定专用批量装(包) 12 8.50 102.00"
                + "\n实收：324.40\n地址：清流镇测试街道";
        byte[] previewPng = renderer.renderReceiptPreview(receipt);
        BufferedImage preview = ImageIO.read(new java.io.ByteArrayInputStream(previewPng));
        byte[] raster = renderer.renderReceipt(receipt);
        byte[] previewRaster = renderer.renderImage(previewPng);

        assertEquals(576, preview.getWidth());
        assertTrue(preview.getHeight() > 500, "long product names should add continuation lines");
        assertArrayEquals(new byte[]{0x1b, '@', 0x1d, 'v', '0', 0, 72, 0},
                java.util.Arrays.copyOf(previewRaster, 8));
        assertTrue(raster.length > 8);
    }

    @Test
    void joinsSplitProductNameAndNumericCellsBeforeRendering() {
        String[] source = {"品名 数量 单价 金额", "20多联B 管(根)", "1 4.50 4.50", "下一商品 2 3.00 6.00"};
        LegacyEscPosRenderer.TableLayout layout = LegacyEscPosRenderer.formatTableRows(source, Set.of(0, 1, 2, 3));

        assertEquals(new LegacyEscPosRenderer.TableLine("20多联B 管(根)", "1", "4.50", "4.50"),
                layout.rows().get(1));
        assertTrue(layout.continuationRows().contains(2));
        assertEquals(new LegacyEscPosRenderer.TableLine("下一商品", "2", "3.00", "6.00"), layout.rows().get(3));
    }

    @Test
    void alignsTableColumnStartsByMeasuredFontWidthAtLargeSpacing() {
        String[] source={"品名 数量 单价 金额","水 2 1.00 2.00","Long product name 1 3.00 3.00"};
        String[] secondCells={"数量","2","1"};
        Font font=new Font("SansSerif",Font.PLAIN,20);
        Map<Integer,String> rows=LegacyEscPosRenderer.alignTableRows(source,Set.of(0,1,2),20,font);
        BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        try{
            graphics.setFont(font);FontMetrics metrics=graphics.getFontMetrics();
            int expected=-1;
            for(int index=0;index<source.length;index++){
                String row=rows.get(index);int secondColumn=row.indexOf(secondCells[index]);
                int start=metrics.stringWidth(row.substring(0,secondColumn));
                if(expected<0)expected=start;
                else assertTrue(Math.abs(expected-start)<=metrics.stringWidth(" "),
                        "table column starts should remain aligned within one rendered space");
            }
        }finally{graphics.dispose();}
    }

    @Test
    void appliesEditableLayoutAndRejectsOutOfRangeSettings() throws Exception {
        String text="清流镇同利五金电器销售部\n销售单\n品名 数量 单价 金额\nPVC小胶水(瓶) 1 2.45 4.90\n合计金额:4.90";
        LegacyEscPosRenderer.LayoutSettings defaults=LegacyEscPosRenderer.LayoutSettings.defaults();
        LegacyEscPosRenderer.LayoutSettings edited=new LegacyEscPosRenderer.LayoutSettings(
                28,40,24,26,8,4,true,true,true,false,"center","center","left","right");
        assertFalse(defaults.bodyBold());
        assertFalse(java.util.Arrays.equals(renderer.renderReceiptPreview(text,defaults),
                renderer.renderReceiptPreview(text,edited)));
        assertThrows(IllegalArgumentException.class,()->renderer.renderReceipt(text,
                new LegacyEscPosRenderer.LayoutSettings(28,34,20,24,21,2,true,true,false,false,
                        "center","center","left","left")));
    }

    private int indexOf(byte[] bytes, byte[] target) {
        outer: for (int i = 0; i <= bytes.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (bytes[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }
}

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
        byte[] preview = renderer.renderReceiptPreview("清流镇同利五金电器销售部\n销售单\n商品  数量\n合计金额  12.00");
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(preview));
        byte[] printable = renderer.renderReceipt("清流镇同利五金电器销售部\n销售单\n商品  数量\n合计金额  12.00");

        assertNotNull(image);
        assertEquals(576, image.getWidth());
        assertTrue(image.getHeight() >= 120);
        assertArrayEquals(new byte[]{0x1b, '@', 0x1d, 'v', '0', 0, 72, 0},
                java.util.Arrays.copyOf(printable, 8));
        assertArrayEquals(printable, renderer.renderImage(preview));
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

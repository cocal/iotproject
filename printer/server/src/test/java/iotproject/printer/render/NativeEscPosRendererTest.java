package iotproject.printer.render;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeEscPosRendererTest {
    @Test
    void emitsNativeTextFormattingAndSmallerPayloadThanRaster() throws Exception {
        String receipt = "清流镇同利五金电器销售部\n销售单\n品名 数量 单价 金额\n螺丝 2 12.50 25.00\n合计金额：25.00";
        var layout = NativeEscPosRenderer.Settings.defaults();
        var nativeRenderer = new NativeEscPosRenderer();

        byte[] nativeBytes = nativeRenderer.render(receipt, layout);
        byte[] preview = nativeRenderer.preview(receipt, layout);

        assertThat(new String(nativeBytes, Charset.forName("GBK"))).contains("销售单", "-".repeat(48))
                .doesNotContain("合计金额");
        assertThat(indexOf(nativeBytes, new byte[]{0x1b, 'M', 0})).isGreaterThanOrEqualTo(0);
        assertThat(indexOf(nativeBytes, new byte[]{0x1b, '3', 40})).isGreaterThanOrEqualTo(0);
        assertThat(indexOf(nativeBytes, new byte[]{0x1d, '!', 0x00})).isGreaterThanOrEqualTo(0);
        assertThat(ImageIO.read(new ByteArrayInputStream(preview))).isNotNull();
    }

    @Test
    void validatesOnlyPrinterParameterRanges() {
        var defaults = NativeEscPosRenderer.Settings.defaults();
        var invalid = new NativeEscPosRenderer.Settings("A", 9, 1, 2, 2, 1, 1, 1, 1, 4, 3,
                false, true, false, true, "center", "center", "left", "left", null);
        assertThat(defaults.validated()).isEqualTo(defaults);
        org.assertj.core.api.Assertions.assertThatThrownBy(invalid::validated)
                .isInstanceOf(IllegalArgumentException.class).hasMessage("native_layout_invalid");
    }

    @Test
    void appliesReorderedSectionsCustomTextAndBlockSpacing() throws Exception {
        String receipt = "清流镇同利五金电器销售部\n销售单\n合计金额：25.00";
        var defaults = NativeEscPosRenderer.Settings.defaults();
        var blocks = List.of(
                new NativeEscPosRenderer.LayoutBlock("section", "title", "", 7, 0, 2, 2, true, "center"),
                new NativeEscPosRenderer.LayoutBlock("text", "", "售后请保留小票", 9, 0, 1, 1, false, "left"),
                new NativeEscPosRenderer.LayoutBlock("section", "company", "", 0, 0, 1, 1, false, "center"));
        var layout = new NativeEscPosRenderer.Settings(defaults.font(), defaults.companyWidth(), defaults.companyHeight(),
                defaults.titleWidth(), defaults.titleHeight(), defaults.bodyWidth(), defaults.bodyHeight(),
                defaults.totalWidth(), defaults.totalHeight(), defaults.lineSpacingDots(), defaults.tableSpaces(),
                defaults.companyBold(), defaults.titleBold(), defaults.bodyBold(), defaults.totalBold(),
                defaults.companyAlign(), defaults.titleAlign(), defaults.bodyAlign(), defaults.totalAlign(), blocks);
        byte[] output = new NativeEscPosRenderer().render(receipt, layout);
        byte[] preview = new NativeEscPosRenderer().preview(receipt, layout);
        byte[] title = "销售单".getBytes(Charset.forName("GBK"));
        byte[] note = "售后请保留小票".getBytes(Charset.forName("GBK"));
        assertThat(indexOf(output, title)).isLessThan(indexOf(output, note));
        assertThat(indexOf(output, note)).isLessThan(indexOf(output, "清流镇同利五金电器销售部".getBytes(Charset.forName("GBK"))));
        assertThat(indexOf(output, new byte[]{0x1b, 'J', 7})).isGreaterThanOrEqualTo(0);
        assertThat(indexOf(output, new byte[]{0x1b, 'J', 9})).isGreaterThanOrEqualTo(0);
        assertThat(ImageIO.read(new ByteArrayInputStream(preview))).isNotNull();
    }

    @Test
    void keepsNativeTableColumnsInside72MillimeterPrintableWidth() {
        String longName = "超长商品名称测试包装材料甲乙丙丁戊己庚辛壬癸";
        String receipt = "店名\n销售单\n品名 数量 单价 金额\n螺丝 2 12.50 25.00\n" + longName + " 10 3.00 30.00\n合计金额：55.00";
        byte[] output = new NativeEscPosRenderer().render(receipt, NativeEscPosRenderer.Settings.defaults());
        Charset gbk = Charset.forName("GBK");

        String decoded = new String(output, gbk);
        assertThat(decoded).containsPattern("品名 +数量 +单价 +金额");
        assertThat(decoded).containsPattern("螺丝 +2 +12\\.50 +25\\.00");
        assertThat(indexOf(output, "壬癸".getBytes(gbk))).isGreaterThanOrEqualTo(0);
        assertThat(new String(output, gbk)).contains("-".repeat(48));
    }

    @Test
    void centersNativePreviewTextWithinTheFullPrintableWidth() throws Exception {
        var defaults = NativeEscPosRenderer.Settings.defaults();
        var blocks = List.of(new NativeEscPosRenderer.LayoutBlock(
                "section", "title", "", 0, 0, 1, 1, true, "center"));
        var layout = new NativeEscPosRenderer.Settings(defaults.font(), defaults.companyWidth(), defaults.companyHeight(),
                defaults.titleWidth(), defaults.titleHeight(), defaults.bodyWidth(), defaults.bodyHeight(),
                defaults.totalWidth(), defaults.totalHeight(), defaults.lineSpacingDots(), defaults.tableSpaces(),
                defaults.companyBold(), defaults.titleBold(), defaults.bodyBold(), defaults.totalBold(),
                defaults.companyAlign(), defaults.titleAlign(), defaults.bodyAlign(), defaults.totalAlign(), blocks);

        var image = ImageIO.read(new ByteArrayInputStream(new NativeEscPosRenderer().preview("销售单", layout)));
        int minX = image.getWidth(), maxX = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) & 0x00ffffff) == 0) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }

        assertThat(image.getWidth()).isEqualTo(576);
        assertThat(minX).isGreaterThanOrEqualTo(252);
        assertThat(maxX).isLessThan(324);
    }

    @Test
    void ignoresLeadingSpacesWhenCenteringCompanyName() throws Exception {
        String company = "清流镇同利五金电器销售部";
        String receipt = "          " + company + "\n销售单";
        var defaults = NativeEscPosRenderer.Settings.defaults();
        var output = new NativeEscPosRenderer().render(receipt, defaults);
        assertThat(new String(output, Charset.forName("GBK"))).contains(company + "\n");
        assertThat(new String(output, Charset.forName("GBK"))).doesNotContain("          " + company);

        var image = ImageIO.read(new ByteArrayInputStream(new NativeEscPosRenderer().preview(receipt, defaults)));
        int minX = image.getWidth(), maxX = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            if ((image.getRGB(x, y) & 0x00ffffff) == 0) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
        }
        int center = (minX + maxX) / 2;
        assertThat(center).isBetween(284, 292);
    }

    @Test
    void skipsWarehouseAndMovesAddressAfterReceivedAmount() {
        String receipt = "店名\n销售单\n仓库：中央仓\n品名 数量 单价 金额\n螺丝 2 12.00 24.00\n"
                + "合计金额：24.00\n实收金额：24.00\n地址：清流镇新街 12 号";
        String output = new String(new NativeEscPosRenderer().render(receipt, NativeEscPosRenderer.Settings.defaults()),
                Charset.forName("GBK"));

        assertThat(output).doesNotContain("仓库", "中央仓", "合计金额");
        assertThat(output.indexOf("实收金额")).isLessThan(output.indexOf("地址：清流镇新街"));
        byte[] address = "地址：清流镇新街".getBytes(Charset.forName("GBK"));
        byte[] rendered = new NativeEscPosRenderer().render(receipt, NativeEscPosRenderer.Settings.defaults());
        int addressOffset = indexOf(rendered, address);
        assertThat(addressOffset).isGreaterThan(0);
        assertThat(indexOfBefore(rendered, new byte[]{0x1b, 'J', 72}, addressOffset)).isGreaterThan(0);
        assertThat(indexOfBefore(rendered, new byte[]{0x1b, 'M', 1}, addressOffset)).isGreaterThan(0);
    }

    private int indexOfBefore(byte[] source, byte[] target, int limit) {
        outer: for (int i = 0; i <= Math.min(source.length, limit) - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer;
            return i;
        }
        return -1;
    }

    @Test
    void defaultNativeLayoutUsesReadableSpacingAndDistinctHeadingScale() {
        var settings = NativeEscPosRenderer.Settings.defaults();

        assertThat(settings.lineSpacingDots()).isEqualTo(16);
        assertThat(settings.companyWidth()).isEqualTo(1);
        assertThat(settings.companyHeight()).isEqualTo(1);
        assertThat(settings.titleWidth()).isEqualTo(2);
        assertThat(settings.titleHeight()).isEqualTo(1);
        assertThat(settings.blocks()).filteredOn(block -> "section".equals(block.type())
                && "title".equals(block.section())).singleElement().satisfies(block -> {
            assertThat(block.width()).isEqualTo(2);
            assertThat(block.height()).isEqualTo(1);
            assertThat(block.bold()).isTrue();
        });
        assertThat(settings.blocks()).filteredOn(block -> "section".equals(block.type())
                && "address".equals(block.section())).hasSize(1);
    }

    private int indexOf(byte[] bytes, byte[] expected) {
        for (int i = 0; i <= bytes.length - expected.length; i++) {
            boolean match = true;
            for (int j = 0; j < expected.length; j++) if (bytes[i + j] != expected[j]) { match = false; break; }
            if (match) return i;
        }
        return -1;
    }
}

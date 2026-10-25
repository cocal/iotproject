package iotproject.printer.render;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EscPosReceiptRendererTest {
    @Test
    void rendersGbkReceiptWithPrinterInitializationAndCut() {
        byte[] result = new EscPosReceiptRenderer().render(Map.of(
                "customer", "测试客户",
                "time", "2026-10-24 10:00",
                "invoiceNo", "XS001",
                "items", List.of("螺丝 2 盒 5.00 10.00"),
                "total", "10.00",
                "received", "10.00",
                "address", "测试地址"));

        assertThat(result).startsWith(0x1B, 0x40);
        assertThat(new String(result, Charset.forName("GBK"))).contains("测试客户", "螺丝", "10.00");
        assertThat(result).endsWith(0x1D, 0x56, 0x42, 0x00);
    }
}

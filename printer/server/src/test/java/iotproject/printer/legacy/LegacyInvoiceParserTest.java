package iotproject.printer.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyInvoiceParserTest {
    private final LegacyInvoiceParser parser = new LegacyInvoiceParser();

    @Test
    void extractsLegacyInvoiceFieldsAndItems() {
        var parsed = parser.parse(List.of(
                "时间:2026-10-24 10:00",
                "往来单位:测试客户",
                "清单号:XS001",
                "品名 数量 单位 单价 金额",
                "螺丝 2 盒 5.00 10.00",
                "合计金额:10.00",
                "实收:10.00",
                "地址:测试地址"));

        assertThat(parsed).containsEntry("time", "2026-10-24 10:00")
                .containsEntry("customer", "测试客户")
                .containsEntry("invoiceNo", "XS001")
                .containsEntry("total", "10.00")
                .containsEntry("received", "10.00")
                .containsEntry("address", "测试地址");
        assertThat(parsed.get("items")).isEqualTo(List.of("螺丝 2 盒 5.00 10.00"));
    }

    @Test
    void retainsAllLinesWhenNoKnownMarkersExist() {
        var parsed = parser.parse(List.of("legacy line"));

        assertThat(parsed.get("lines")).isEqualTo(List.of("legacy line"));
        assertThat(parsed.get("items")).isEqualTo(List.of());
    }
}

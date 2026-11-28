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
        assertThat(parsed.get("validationErrors")).asList().contains("缺少单号", "缺少地址", "合计金额格式无效", "缺少商品明细");
    }

    @Test
    void createsStructuredItemRowsIncludingSplitNameAndAmounts() {
        var parsed = parser.parse(List.of("单号:XS004", "品名 数量 单价 金额", "20多联B管(根)",
                "1 4.50 4.50", "合计:4.50", "地址:测试地址"));

        assertThat(parsed.get("itemRows")).asList().containsExactly(
                java.util.Map.of("name", "20多联B管(根)", "quantity", "1", "unit", "",
                        "unitPrice", "4.50", "amount", "4.50"));
        assertThat(parsed.get("validationErrors")).asList().isEmpty();
    }

    @Test
    void acceptsMissingCustomerName() {
        var parsed = parser.parse(List.of("单号:XS005", "品名 数量", "测试商品 1",
                "合计:1", "地址:测试地址"));
        assertThat(parsed.get("validationErrors")).asList().isEmpty();
    }

    @Test
    void acceptsExistingSupportedAliasesAndNumericTotals() {
        var parsed = parser.parse(List.of(
                "客户:测试客户", "单号:XS002", "品名 数量", "商品 1", "合计:1,280.50", "地址:测试地址"));

        assertThat(parsed.get("validationErrors")).asList().isEmpty();
    }

    @Test
    void rejectsReplacementAndControlCharacters() {
        var parsed = parser.parse(List.of("\uFFFD malformed", "\u0001 control"));

        assertThat(parsed.get("validationErrors")).asList().contains("包含非法字符");
    }

    @Test
    void rejectsMalformedThousandsSeparators() {
        var parsed = parser.parse(List.of(
                "客户:测试客户", "单号:XS003", "品名 数量", "商品 1", "合计:1,,280.50", "地址:测试地址"));

        assertThat(parsed.get("validationErrors")).asList().contains("合计金额格式无效");
    }
}

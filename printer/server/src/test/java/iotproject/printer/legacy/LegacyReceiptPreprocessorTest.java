package iotproject.printer.legacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyReceiptPreprocessorTest {
    @Test
    void stripsInvisibleCharactersAtLineBoundariesAndKeepsStructuredPayload() {
        var processor = new LegacyReceiptPreprocessor(new LegacyInvoiceParser());
        var result = processor.process("\u200B单号:XS-1\uFEFF\r\n\u0009品名 数量 单价 金额\u0009\r\n测试商品 1 2 2\r\n");

        assertThat(result.normalizedText()).isEqualTo("单号:XS-1\n品名 数量 单价 金额\n测试商品 1 2 2");
        assertThat(result.payload()).containsEntry("invoiceNo", "XS-1");
        assertThat(result.payload().get("itemRows")).asList().containsExactly(
                java.util.Map.of("name", "测试商品", "quantity", "1", "unit", "",
                        "unitPrice", "2", "amount", "2"));
    }
}

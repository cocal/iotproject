package iotproject.printer.render;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/** Generates the byte stream accepted by Xprinter 58/76/80mm ESC/POS models. */
public class EscPosReceiptRenderer {
    private static final Charset GBK = Charset.forName("GBK");
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;

    public byte[] render(Map<String, Object> payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, ESC, '@');
        write(out, ESC, 'a', 1);
        line(out, "销售单");
        write(out, ESC, 'a', 0);
        line(out, field("客户", payload.get("customer")));
        line(out, field("时间", payload.get("time")));
        line(out, field("单号", payload.get("invoiceNo")));
        line(out, "--------------------------------");

        Object items = payload.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                line(out, String.valueOf(item));
            }
        }
        line(out, "--------------------------------");
        line(out, field("合计", payload.get("total")));
        line(out, field("实收", payload.get("received")));
        line(out, field("备注", payload.get("note")));
        line(out, field("地址", payload.get("address")));
        line(out, "\n");
        write(out, GS, 'V', 66, 0);
        return out.toByteArray();
    }

    private String field(String label, Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? label + ":" : label + ":" + text;
    }

    private void line(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(GBK));
        out.write('\n');
    }

    private void write(ByteArrayOutputStream out, int... bytes) {
        for (int value : bytes) {
            out.write(value);
        }
    }
}

package iotproject.printer.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LegacyInvoiceParser {
    public Map<String, Object> parse(List<String> lines) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", List.copyOf(lines));
        result.put("time", firstContaining(lines, "时间"));
        result.put("customer", firstContaining(lines, "往来单位", "客户"));
        result.put("invoiceNo", firstContaining(lines, "清单号", "单号"));
        result.put("total", firstContaining(lines, "合计金额", "合计"));
        result.put("received", firstContaining(lines, "实收"));
        result.put("note", firstContaining(lines, "备注"));
        result.put("address", firstContaining(lines, "地址"));
        result.put("items", extractItems(lines));
        return result;
    }

    private String firstContaining(List<String> lines, String... markers) {
        for (String line : lines) {
            for (String marker : markers) {
                int markerIndex = line.indexOf(marker);
                if (markerIndex >= 0) {
                    String value = line.substring(markerIndex + marker.length()).trim();
                    if (value.startsWith(":") || value.startsWith("：")) {
                        value = value.substring(1).trim();
                    }
                    return value.isEmpty() ? line.trim() : value;
                }
            }
        }
        return "";
    }

    private List<String> extractItems(List<String> lines) {
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("品名")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return List.of();
        }

        List<String> items = new ArrayList<>();
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.contains("合计") || line.contains("实收") || line.contains("地址")) {
                break;
            }
            if (!line.isEmpty()) {
                items.add(line);
            }
        }
        return items;
    }
}

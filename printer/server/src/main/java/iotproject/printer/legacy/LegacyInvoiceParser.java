package iotproject.printer.legacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class LegacyInvoiceParser {
    private static final Pattern AMOUNT = Pattern.compile(
            "[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?|\\.\\d{1,2})");

    public Map<String, Object> parse(List<String> lines) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lines", List.copyOf(lines));
        result.put("time", firstContaining(lines, "时间"));
        result.put("customer", firstContaining(lines, "往来单位", "客户"));
        result.put("username", firstContaining(lines, "用户名", "操作员"));
        result.put("invoiceNo", firstContaining(lines, "清单号", "单号"));
        result.put("total", firstContaining(lines, "合计金额", "合计"));
        result.put("received", firstContaining(lines, "实收"));
        result.put("note", firstContaining(lines, "备注"));
        result.put("address", firstContaining(lines, "地址"));
        result.put("items", extractItems(lines));
        result.put("itemRows", normalizeItemRows(extractItems(lines)));
        result.put("validationErrors", validationErrors(result));
        return result;
    }

    public List<String> validationErrors(Map<String, Object> payload) {
        List<String> errors = new ArrayList<>();
        requireValue(payload, "invoiceNo", "缺少单号", errors);
        requireValue(payload, "address", "缺少地址", errors);
        Object total = payload.get("total");
        if (!(total instanceof String value) || !AMOUNT.matcher(value.trim()).matches()) {
            errors.add("合计金额格式无效");
        }
        Object items = payload.get("items");
        if (!(items instanceof List<?> list) || list.isEmpty()) {
            errors.add("缺少商品明细");
        }
        Object lines = payload.get("lines");
        if (lines instanceof List<?> inputLines && inputLines.stream().anyMatch(line ->
                line instanceof String text && (text.indexOf('\uFFFD') >= 0
                        || text.chars().anyMatch(ch -> ch < 0x20 && ch != '\t')))) {
            errors.add("包含非法字符");
        }
        return List.copyOf(errors);
    }

    private void requireValue(Map<String, Object> payload, String key, String error, List<String> errors) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(error);
        }
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
                    return value;
                }
            }
        }
        return "";
    }

    private List<Map<String, String>> normalizeItemRows(List<String> rawItems) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> pendingName = new ArrayList<>();
        for (String raw : rawItems) {
            String[] cells = raw.trim().split("[\\t ]+");
            int numericCells = trailingNumericCells(cells);
            if (numericCells >= 3) {
                int start = cells.length - numericCells;
                List<String> nameParts = new ArrayList<>(pendingName);
                nameParts.addAll(List.of(cells).subList(0, start));
                String name = String.join(" ", nameParts).trim();
                if (!name.isEmpty()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("name", name);
                    row.put("quantity", cells[start]);
                    row.put("unit", numericCells == 4 ? cells[start + 1] : "");
                    row.put("unitPrice", cells[cells.length - 2]);
                    row.put("amount", cells[cells.length - 1]);
                    result.add(row);
                }
                pendingName.clear();
            } else {
                pendingName.add(raw.trim());
            }
        }
        return List.copyOf(result);
    }

    private int trailingNumericCells(String[] cells) {
        int count = 0;
        for (int i = cells.length - 1; i >= 0 && i >= cells.length - 4; i--) {
            if (AMOUNT.matcher(cells[i]).matches()) count++;
            else break;
        }
        return count >= 3 ? count : 0;
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

package com.internalops.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ImportValidationService {
    private final JdbcTemplate jdbc;

    public ImportValidationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ParsedImportRow> validateAll(ImportType type, List<ParsedImportRow> rows) {
        Map<String, Integer> firstInventoryRows = new LinkedHashMap<>();
        List<ParsedImportRow> result = new ArrayList<>();
        for (ParsedImportRow row : rows) {
            if (row.status() == ImportRowStatus.IGNORED || row.status() == ImportRowStatus.ERROR) {
                result.add(row);
                continue;
            }
            if (type == ImportType.INVENTORY) {
                String sku = text(row.data(), "skuCode");
                if (!sku.isBlank()) {
                    Integer firstRow = firstInventoryRows.putIfAbsent(sku.toUpperCase(Locale.ROOT), row.rowNumber());
                    if (firstRow != null) {
                        Map<String, Object> data = new LinkedHashMap<>(row.data());
                        data.put("skuCode", sku);
                        result.add(new ParsedImportRow(row.sheetName(), row.rowNumber(),
                                ImportRowStatus.IGNORED, data,
                                "重复 SKU，已采用第 " + firstRow + " 行数据"));
                        continue;
                    }
                }
            }
            ParsedImportRow validated = validate(type, row.sheetName(), row.rowNumber(), row.data());
            result.add(validated);
        }
        return result.stream().map(row -> withConflict(type, row)).toList();
    }

    public ParsedImportRow validate(ImportType type, String sheet, int rowNumber, Map<String, Object> source) {
        Map<String, Object> data = new LinkedHashMap<>(source);
        return switch (type) {
            case CUSTOMER -> validateCustomer(sheet, rowNumber, data);
            case COST -> validateCost(sheet, rowNumber, data);
            case INVENTORY -> validateInventory(sheet, rowNumber, data);
            case SUPPLIER -> validateSupplier(sheet, rowNumber, data);
        };
    }

    public ParsedImportRow withConflict(ImportType type, ParsedImportRow row) {
        Map<String, Object> data = new LinkedHashMap<>(row.data());
        if (row.status() != ImportRowStatus.VALID) {
            data.remove("_conflict");
            data.remove("_conflictAction");
            return new ParsedImportRow(row.sheetName(), row.rowNumber(), row.status(), data, row.errorMessage());
        }
        boolean conflict = hasConflict(type, data);
        data.put("_conflict", conflict);
        data.put("_conflictAction", conflict && "OVERWRITE".equals(text(data, "_conflictAction")) ? "OVERWRITE" : "SKIP");
        return new ParsedImportRow(row.sheetName(), row.rowNumber(), row.status(), data, row.errorMessage());
    }

    public boolean hasConflict(ImportType type, Map<String, Object> data) {
        try {
            return switch (type) {
                case CUSTOMER -> jdbc.query("SELECT customer_name FROM customer", (rs, index) -> rs.getString(1))
                        .stream().map(this::normalizeCustomer).anyMatch(normalizeCustomer(text(data, "customerName"))::equals);
                case COST, INVENTORY -> jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE sku_code=?", Integer.class,
                        text(data, "skuCode")) > 0;
                case SUPPLIER -> jdbc.query("SELECT supplier_name FROM supplier", (rs, index) -> rs.getString(1))
                        .stream().map(this::normalizeCustomer).anyMatch(normalizeCustomer(text(data, "supplierName"))::equals);
            };
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ParsedImportRow validateCustomer(String sheet, int row, Map<String, Object> data) {
        String name = text(data, "customerName").replace('(', '（').replace(')', '）').trim();
        data.put("customerName", name);
        return name.isBlank() ? error(sheet, row, data, "客户名称不能为空") : valid(sheet, row, data);
    }

    private ParsedImportRow validateSupplier(String sheet, int row, Map<String, Object> data) {
        String name = text(data, "supplierName").replace('(', '（').replace(')', '）').trim();
        data.put("supplierName", name);
        return name.isBlank() ? error(sheet, row, data, "供应商名称不能为空") : valid(sheet, row, data);
    }

    private ParsedImportRow validateCost(String sheet, int row, Map<String, Object> data) {
        BigDecimal cost;
        try {
            cost = decimal(data.get("cost"));
        } catch (NumberFormatException exception) {
            return error(sheet, row, data, "成本单价格式错误");
        }
        if (cost == null || cost.signum() <= 0) return error(sheet, row, data, "成本单价必须大于 0");
        data.put("cost", cost);
        String sku = text(data, "skuCode");
        if (!sku.isBlank()) {
            data.put("skuCode", sku);
            data.remove("_autoSku");
            return valid(sheet, row, data);
        }
        List<String> matches;
        try {
            matches = jdbc.query("SELECT DISTINCT s.sku_code FROM inventory_balance b JOIN sku s ON s.id=b.sku_id WHERE LOWER(TRIM(COALESCE(s.model,'')))=? " +
                            "AND LOWER(TRIM(COALESCE(color,'')))=? AND LOWER(TRIM(COALESCE(lock_body,'')))=?",
                    (rs, index) -> rs.getString(1), normalize(text(data, "model")),
                    normalize(text(data, "color")), normalize(text(data, "lockBody")));
        } catch (RuntimeException exception) {
            matches = List.of();
        }
        if (matches.size() == 1) {
            data.put("skuCode", matches.get(0));
            data.remove("_autoSku");
            return valid(sheet, row, data);
        }
        // 成本表常常不带物料编号。无法唯一反查库存时仍保留这一行，
        // 提交时以空物料编号保存，避免把有效的成本数据静默丢弃。
        data.put("_autoSku", true);
        return valid(sheet, row, data);
    }

    private ParsedImportRow validateInventory(String sheet, int row, Map<String, Object> data) {
        String sku = text(data, "skuCode");
        if (sku.isBlank()) return error(sheet, row, data, "物料编号 SKU 不能为空");
        try {
            int actual = integer(data, "actualQuantity");
            int locked = integer(data, "lockedQuantity");
            int transit = integer(data, "inTransitQuantity");
            if (actual < 0 || locked < 0 || transit < 0 || locked > actual + transit) {
                return error(sheet, row, data, "库存数量无效或锁定库存大于实际与在途库存之和");
            }
            data.put("skuCode", sku);
            data.put("actualQuantity", actual);
            data.put("lockedQuantity", locked);
            data.put("inTransitQuantity", transit);
            return valid(sheet, row, data);
        } catch (NumberFormatException exception) {
            return error(sheet, row, data, "库存数量必须为整数");
        }
    }

    private ParsedImportRow valid(String sheet, int row, Map<String, Object> data) {
        return new ParsedImportRow(sheet, row, ImportRowStatus.VALID, data, null);
    }

    private ParsedImportRow error(String sheet, int row, Map<String, Object> data, String message) {
        return new ParsedImportRow(sheet, row, ImportRowStatus.ERROR, data, message);
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String normalize(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.endsWith(".0") ? normalized.substring(0, normalized.length() - 2) : normalized;
    }

    private String normalizeCustomer(String value) {
        return value.trim().replace('(', '（').replace(')', '）').replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private BigDecimal decimal(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return new BigDecimal(value.toString().replace(",", ""));
    }

    private int integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) return 0;
        return new BigDecimal(value.toString()).intValueExact();
    }
}

package com.internalops.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ImportCommitService {
    private static final List<String> LOCKED_SOURCES = List.of(
            "\u94ED\u7231\u94A7\u4E54", "\u535A\u4E50\u9F99\u7C73", "\u8001\u631D", "\u8D1D\u6717", "\u9A6C\u6765\u897F\u4E9A");
    private final JdbcTemplate jdbc;
    private final ImportBatchRepository repository;

    public ImportCommitService(JdbcTemplate jdbc, ImportBatchRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Transactional
    public ImportBatchView commit(long batchId) {
        return commit(batchId, null);
    }

    @Transactional
    public ImportBatchView commit(long batchId, ImportConflictPolicy policy) {
        ImportBatchView batch = repository.findBatch(batchId);
        if ("COMMITTED".equals(batch.status())) return batch;
        if (repository.isAppendOnly(batchId)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("appended", batch.totalRows());
            result.put("created", 0);
            result.put("updated", 0);
            result.put("skipped", 0);
            result.put("errors", 0);
            result.put("ignored", 0);
            result.put("mode", "APPEND_ONLY_RAW_WORKBOOK");
            repository.markCommitted(batchId, batch.totalRows(), result);
            return repository.findBatch(batchId);
        }
        int created = 0;
        int updated = 0;
        int committed = 0;
        int skipped = 0;
        for (ImportRowView row : batch.rows()) {
            if (row.status() != ImportRowStatus.VALID) continue;
            boolean conflict = Boolean.TRUE.equals(row.data().get("_conflict"));
            if (shouldSkip(row, conflict, policy)) {
                skipped++;
                continue;
            }
            boolean wasCreated = switch (batch.importType()) {
                case CUSTOMER -> commitCustomer(row.data());
                case INVENTORY -> commitInventory(batchId, row.data());
                case COST -> commitCost(batchId, row.data());
            };
            if (wasCreated) created++; else updated++;
            committed++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("committed", committed);
        result.put("errors", batch.errorRows());
        result.put("ignored", batch.ignoredRows());
        result.put("skipped", skipped);
        result.put("policy", policy == null ? "ROW_OVERRIDE" : policy.name());
        repository.markCommitted(batchId, committed, result);
        return repository.findBatch(batchId);
    }

    private boolean shouldSkip(ImportRowView row, boolean conflict, ImportConflictPolicy policy) {
        if (policy == null) return conflict && !"OVERWRITE".equals(text(row.data(), "_conflictAction"));
        return switch (policy) {
            case UPSERT_KEEP_EXISTING_ON_BLANK -> false;
            case SKIP_CONFLICTS -> conflict;
            case UPDATE_EXISTING_ONLY -> !conflict;
        };
    }

    private boolean commitCustomer(Map<String, Object> data) {
        String name = text(data, "customerName");
        Long existing = findByNormalizedName("customer", "customer_name", name);
        if (existing != null) {
            jdbc.update("UPDATE customer SET customer_name=?,enabled=TRUE WHERE id=?", name, existing);
            return false;
        }
        jdbc.update("INSERT INTO customer(customer_code,customer_name,enabled) VALUES(?,?,TRUE)", nextCode("customer", "customer_code", "CUS"), name);
        return true;
    }

    private boolean commitInventory(long batchId, Map<String, Object> data) {
        SkuResult sku = upsertSku(data);
        String supplier = text(data, "supplierName");
        if (!supplier.isBlank() && findByNormalizedName("supplier", "supplier_name", supplier) == null) {
            jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,enabled) VALUES(?,?,TRUE)",
                    nextCode("supplier", "supplier_code", "SUP"), supplier);
        }
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE ORDER BY id LIMIT 1", Long.class);
        int actual = integer(data, "actualQuantity");
        int locked = integer(data, "lockedQuantity");
        int transit = integer(data, "inTransitQuantity");
        String sourceSupplier = text(data, "supplierName");
        String inventoryRemark = text(data, "remark");
        List<int[]> old = jdbc.query("SELECT actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=?",
                (rs, index) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)}, warehouseId, sku.id());
        int[] before = old.isEmpty() ? new int[]{0, 0, 0} : old.get(0);
        if (old.isEmpty()) {
            jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,source_supplier_name,inventory_remark) VALUES(?,?,?,?,?,?,?)",
                    warehouseId, sku.id(), actual, locked, transit, emptyToNull(sourceSupplier), emptyToNull(inventoryRemark));
        } else {
            jdbc.update("UPDATE inventory_balance SET actual_quantity=?,locked_quantity=?,in_transit_quantity=?,source_supplier_name=?,inventory_remark=?,version=version+1 WHERE warehouse_id=? AND sku_id=?",
                    actual, locked, transit, emptyToNull(sourceSupplier), emptyToNull(inventoryRemark), warehouseId, sku.id());
        }
        long balanceId = jdbc.queryForObject("SELECT id FROM inventory_balance WHERE warehouse_id=? AND sku_id=?", Long.class,
                warehouseId, sku.id());
        replaceLockedAllocations(balanceId, data);
        jdbc.update("DELETE FROM inventory_transaction WHERE warehouse_id=? AND sku_id=? AND business_type='EXCEL_IMPORT_HISTORY'",
                warehouseId, sku.id());
        if (before[0] != actual || before[1] != locked || before[2] != transit) {
            jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no," +
                            "actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after,transit_before,transit_after) " +
                            "VALUES(?,?,'INITIAL_IMPORT','EXCEL_IMPORT',?,?,?,?,?,?,?,?,?,?)",
                    warehouseId, sku.id(), String.valueOf(batchId), actual - before[0], locked - before[1], transit - before[2],
                    before[0], actual, before[1], locked, before[2], transit);
        }
        persistInventoryHistory(batchId, sku.id(), warehouseId, actual, locked, transit, data.get("movements"));
        return sku.created();
    }

    private void replaceLockedAllocations(long balanceId, Map<String, Object> data) {
        jdbc.update("DELETE FROM inventory_locked_allocation WHERE inventory_balance_id=?", balanceId);
        for (int index = 0; index < LOCKED_SOURCES.size(); index++) {
            jdbc.update("INSERT INTO inventory_locked_allocation(inventory_balance_id,lock_source,quantity) VALUES(?,?,?)",
                    balanceId, LOCKED_SOURCES.get(index), integer(data, "lockedBreakdown" + (index + 1)));
        }
    }

    private void persistInventoryHistory(long batchId, long skuId, long warehouseId, int actual, int locked, int transit,
                                         Object movementData) {
        if (!(movementData instanceof List<?> movements)) return;
        for (Object rawMovement : movements) {
            if (!(rawMovement instanceof Map<?, ?> movement)) continue;
            String direction = mapText(movement, "direction");
            if (!"入库".equals(direction) && !"出库".equals(direction)) continue;
            int quantity = mapInteger(movement, "quantity");
            if (quantity == 0) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(mapText(movement, "date"));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("库存历史日期无效", exception);
            }
            String sourceColumn = mapText(movement, "sourceColumn");
            String businessNo = truncate("B" + batchId + ":" + sourceColumn, 80);
            String transactionType = "入库".equals(direction) ? "EXCEL_INBOUND" : "EXCEL_OUTBOUND";
            jdbc.update("INSERT INTO inventory_transaction(warehouse_id,sku_id,transaction_type,business_type,business_no," +
                            "actual_delta,locked_delta,transit_delta,actual_before,actual_after,locked_before,locked_after," +
                            "transit_before,transit_after,operated_at) VALUES(?,? ,?,'EXCEL_IMPORT_HISTORY',?,?,?,?,?,?,?,?,?,?,?)",
                    warehouseId, skuId, transactionType, businessNo, quantity,
                    0, 0, actual, actual, locked, locked, transit, transit, Timestamp.valueOf(date.atStartOfDay()));
        }
    }

    private boolean commitCost(long batchId, Map<String, Object> data) {
        SkuResult sku = upsertSku(data);
        BigDecimal newCost = decimal(data.get("cost"));
        BigDecimal factoryPrice = optionalDecimal(data.get("factoryPrice"));
        String remark = text(data, "remark");
        BigDecimal oldCost = jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=?", BigDecimal.class, sku.id());
        if (oldCost == null || oldCost.compareTo(newCost) != 0) {
            jdbc.update("UPDATE sku SET current_cost=?,factory_price=?,product_remark=? WHERE id=?", newCost, factoryPrice, emptyToNull(remark), sku.id());
            jdbc.update("INSERT INTO sku_cost_history(sku_id,old_cost,new_cost,source_type,source_no,effective_at) VALUES(?,?,?,'EXCEL_IMPORT',?,?)",
                    sku.id(), oldCost, newCost, String.valueOf(batchId), Timestamp.from(Instant.now()));
        }
        if (oldCost != null && oldCost.compareTo(newCost) == 0) {
            jdbc.update("UPDATE sku SET factory_price=?,product_remark=? WHERE id=?", factoryPrice, emptyToNull(remark), sku.id());
        }
        return sku.created();
    }

    private SkuResult upsertSku(Map<String, Object> data) {
        String code = text(data, "skuCode");
        List<Long> ids = code.isBlank() ? List.of() : jdbc.query("SELECT id FROM sku WHERE sku_code=?", (rs, index) -> rs.getLong(1), code);
        String model = text(data, "model");
        String configuration = text(data, "configuration");
        String productName = !model.isBlank() ? model : (!configuration.isBlank() ? truncate(configuration, 200) : code);
        String color = text(data, "color");
        String lockBody = text(data, "lockBody");
        String version = text(data, "version");
        String unit = text(data, "unit");
        if (ids.isEmpty()) {
            if (unit.isBlank()) unit = "件";
            final Object persistedCode = emptyToNull(code);
            final Object persistedModel = emptyToNull(model);
            final Object persistedColor = emptyToNull(color);
            final Object persistedLockBody = emptyToNull(lockBody);
            final Object persistedVersion = emptyToNull(version);
            final Object persistedConfiguration = emptyToNull(configuration);
            final String persistedUnit = unit;
            final String persistedProductName = productName;
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO sku(sku_code,model,product_name,color,lock_body,product_version,configuration,unit,enabled) VALUES(?,?,?,?,?,?,?,?,TRUE)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setObject(1, persistedCode);
                statement.setObject(2, persistedModel);
                statement.setString(3, persistedProductName);
                statement.setObject(4, persistedColor);
                statement.setObject(5, persistedLockBody);
                statement.setObject(6, persistedVersion);
                statement.setObject(7, persistedConfiguration);
                statement.setString(8, persistedUnit);
                return statement;
            }, keyHolder);
            Long id = keyHolder.getKey().longValue();
            return new SkuResult(id, true);
        }
        long id = ids.get(0);
        Map<String, Object> current = jdbc.queryForMap("SELECT model,product_name,color,lock_body,product_version,configuration,unit FROM sku WHERE id=?", id);
        if (!data.containsKey("version")) version = current.get("product_version") == null ? "" : current.get("product_version").toString();
        if (!data.containsKey("unit")) unit = current.get("unit") == null ? "件" : current.get("unit").toString();
        if (unit.isBlank()) unit = "件";
        model = keepExistingWhenBlank(model, current.get("model"));
        configuration = keepExistingWhenBlank(configuration, current.get("configuration"));
        color = keepExistingWhenBlank(color, current.get("color"));
        lockBody = keepExistingWhenBlank(lockBody, current.get("lock_body"));
        version = keepExistingWhenBlank(version, current.get("product_version"));
        unit = keepExistingWhenBlank(unit, current.get("unit"));
        String existingName = current.get("product_name") == null ? code : current.get("product_name").toString();
        productName = !model.isBlank() ? model : (!configuration.isBlank() ? truncate(configuration, 200) : existingName);
        jdbc.update("UPDATE sku SET model=?,product_name=?,color=?,lock_body=?,product_version=?,configuration=?,unit=?,enabled=TRUE WHERE id=?",
                emptyToNull(model), productName, emptyToNull(color), emptyToNull(lockBody), emptyToNull(version), emptyToNull(configuration), unit, id);
        return new SkuResult(id, false);
    }


    private Long findByNormalizedName(String table, String column, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id," + column + " AS business_name FROM " + table);
        String normalized = normalizeName(name);
        for (Map<String, Object> row : rows) {
            if (normalizeName(String.valueOf(row.get("business_name"))).equals(normalized)) {
                return ((Number) row.get("id")).longValue();
            }
        }
        return null;
    }

    private String nextCode(String table, String column, String prefix) {
        int max = 0;
        for (String code : jdbc.query("SELECT " + column + " FROM " + table + " WHERE " + column + " LIKE ?",
                (rs, index) -> rs.getString(1), prefix + "%")) {
            try { max = Math.max(max, Integer.parseInt(code.substring(prefix.length()))); } catch (RuntimeException ignored) { }
        }
        return prefix + String.format("%06d", max + 1);
    }


    private String normalizeName(String value) {
        return value.trim().replace('(', '（').replace(')', '）').replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String keepExistingWhenBlank(String value, Object existing) {
        return value == null || value.isBlank() ? (existing == null ? "" : existing.toString()) : value;
    }

    private int integer(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null || value.toString().isBlank() ? 0 : new BigDecimal(value.toString()).intValueExact();
    }

    private String mapText(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int mapInteger(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value == null || value.toString().isBlank() ? 0 : new BigDecimal(value.toString()).intValueExact();
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null || value.toString().isBlank() ? null : new BigDecimal(value.toString());
    }

    private Object emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private record SkuResult(long id, boolean created) { }
}

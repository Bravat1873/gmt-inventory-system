package com.internalops.importing;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/import-commit-schema.sql"})
class ImportCommitServiceTest {
    @Autowired ImportBatchRepository repository;
    @Autowired ImportCommitService commitService;
    @Autowired ImportValidationService validationService;
    @Autowired ImportErrorWorkbookService errorWorkbookService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void authorizeCostImports() {
        CurrentUser.set(new CurrentUser(1L, "admin", "管理员", UserRole.ADMIN));
    }

    @AfterEach
    void clearCurrentUser() {
        CurrentUser.clear();
    }

    @Test
    void commitsCustomersInventoryAndCostsIdempotently() throws Exception {
        long customerBatch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "hash-customer", List.of(
                row(ImportRowStatus.VALID, Map.of("customerName", "测试客户"), null)));
        commitService.commit(customerBatch);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class));

        long inventoryBatch = repository.create(ImportType.INVENTORY, "inventory.xlsx", "hash-inventory", List.of(
                row(ImportRowStatus.VALID, Map.ofEntries(
                        Map.entry("customerPartNumber", "SKU-1"), Map.entry("model", "P90"),
                        Map.entry("configuration", "智能锁"), Map.entry("version", "V1"),
                        Map.entry("color", "黑"), Map.entry("lockBody", "7068"), Map.entry("unit", "套"),
                        Map.entry("actualQuantity", 20), Map.entry("lockedQuantity", 15),
                        Map.entry("lockedBreakdown1", 1), Map.entry("lockedBreakdown2", 2),
                        Map.entry("lockedBreakdown3", 3), Map.entry("lockedBreakdown4", 4),
                        Map.entry("lockedBreakdown5", 5),
                        Map.entry("inTransitQuantity", 5), Map.entry("supplierName", "测试供应商")), null)));
        commitService.commit(inventoryBatch);
        assertEquals(20, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_locked_allocation", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT quantity FROM inventory_locked_allocation WHERE lock_source='贝朗'", Integer.class));

        long costBatch = repository.create(ImportType.COST, "cost.xlsx", "hash-cost", List.of(
                row(ImportRowStatus.VALID, Map.of("customerPartNumber", "SKU-1", "model", "P90", "color", "黑",
                        "lockBody", "7068", "configuration", "智能锁", "cost", new BigDecimal("88.50")), null)));
        commitService.commit(costBatch);
        commitService.commit(costBatch);
        assertEquals(0, new BigDecimal("88.50").compareTo(jdbc.queryForObject("SELECT current_cost FROM sku WHERE customer_part_number='SKU-1'", BigDecimal.class)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sku_cost_history", Integer.class));
        assertEquals("套", jdbc.queryForObject("SELECT unit FROM sku WHERE customer_part_number='SKU-1'", String.class));
        assertEquals("V1", jdbc.queryForObject("SELECT product_version FROM sku WHERE customer_part_number='SKU-1'", String.class));
    }

    @Test
    void errorWorkbookContainsChineseReason() throws Exception {
        long batch = repository.create(ImportType.INVENTORY, "bad.xlsx", "hash-error", List.of(
                row(ImportRowStatus.ERROR, Map.of("customerPartNumber", ""), "客户料号 SKU 不能为空")));
        byte[] content = errorWorkbookService.create(batch);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            assertEquals("异常数据", workbook.getSheetAt(0).getSheetName());
            assertEquals("客户料号 SKU 不能为空", workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue());
        }
    }

    @Test
    void skipsConflictingRowsUntilOverwriteIsExplicitlySelected() {
        jdbc.update("INSERT INTO customer(customer_code,customer_name,enabled) VALUES('CUS000001','Existing customer',TRUE)");

        long skippedBatch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "hash-skip", List.of(
                row(ImportRowStatus.VALID, Map.of("customerName", "Existing customer", "_conflict", true,
                        "_conflictAction", "SKIP"), null)));
        var skipped = commitService.commit(skippedBatch);

        assertEquals(0, skipped.committedRows());
        assertEquals(1, ((Number) ((Map<?, ?>) skipped.result()).get("skipped")).intValue());

        long overwriteBatch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "hash-overwrite", List.of(
                row(ImportRowStatus.VALID, Map.of("customerName", "Existing customer", "_conflict", true,
                        "_conflictAction", "OVERWRITE"), null)));
        commitService.commit(overwriteBatch);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM customer", Integer.class));
    }

    @Test
    void batchUpsertPolicyCommitsConflictsWithoutRequiringPerRowOverrides() {
        jdbc.update("INSERT INTO customer(customer_code,customer_name,enabled) VALUES('CUS000001','Existing customer',TRUE)");
        long batch = repository.create(ImportType.CUSTOMER, "customer.xlsx", "hash-batch-policy", List.of(
                row(ImportRowStatus.VALID, Map.of("customerName", "Existing customer", "_conflict", true), null)));

        var committed = commitService.commit(batch, ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK);

        assertEquals(1, committed.committedRows());
        assertEquals("UPSERT_KEEP_EXISTING_ON_BLANK", ((Map<?, ?>) committed.result()).get("policy"));
    }

    @Test
    void upsertPolicyKeepsExistingSkuFieldsWhenTheImportCellIsBlank() {
        jdbc.update("INSERT INTO sku(customer_part_number,model,product_name,color,lock_body,product_version,configuration,unit,enabled) VALUES('SKU-KEEP','P90','P90','black','7068','V1','smart lock','PCS',TRUE)");
        long batch = repository.create(ImportType.COST, "cost.xlsx", "hash-keep-fields", List.of(
                row(ImportRowStatus.VALID, Map.of("customerPartNumber", "SKU-KEEP", "model", "", "color", "", "lockBody", "", "configuration", "", "cost", new BigDecimal("99"), "_conflict", true), null)));

        commitService.commit(batch, ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK);

        assertEquals("P90", jdbc.queryForObject("SELECT model FROM sku WHERE customer_part_number='SKU-KEEP'", String.class));
        assertEquals("black", jdbc.queryForObject("SELECT color FROM sku WHERE customer_part_number='SKU-KEEP'", String.class));
        assertEquals("smart lock", jdbc.queryForObject("SELECT configuration FROM sku WHERE customer_part_number='SKU-KEEP'", String.class));
    }

    @Test
    void createsCostRowsWithoutSkuAsBlankSku() {
        long batch = repository.create(ImportType.COST, "cost.xlsx", "hash-auto-sku", List.of(
                row(ImportRowStatus.VALID, Map.of(
                        "customerPartNumber", "", "_autoSku", true,
                        "model", "P50", "color", "black", "lockBody", "7068",
                        "configuration", "smart lock", "cost", new BigDecimal("465")), null)));

        commitService.commit(batch, ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE customer_part_number IS NULL", Integer.class));
    }

    @Test
    void duplicateInventorySkuCreatesOneProductWithFirstRowInventory() {
        List<ParsedImportRow> rows = validationService.validateAll(ImportType.INVENTORY, List.of(
                new ParsedImportRow("爱迪生", 19, ImportRowStatus.VALID, Map.ofEntries(
                        Map.entry("customerPartNumber", "SXSEL_P90YZH70WPSE-A"),
                        Map.entry("model", "P90"),
                        Map.entry("actualQuantity", 0),
                        Map.entry("lockedQuantity", 0),
                        Map.entry("inTransitQuantity", 120)
                ), null),
                new ParsedImportRow("爱迪生", 21, ImportRowStatus.VALID, Map.ofEntries(
                        Map.entry("customerPartNumber", "sxsel_p90yzh70wpse-a"),
                        Map.entry("model", "P90装饰锁"),
                        Map.entry("actualQuantity", 10),
                        Map.entry("lockedQuantity", 0),
                        Map.entry("inTransitQuantity", 10)
                ), null)
        ));
        long batch = repository.create(ImportType.INVENTORY, "inventory.xlsx", "hash-duplicate-sku", rows);

        var committed = commitService.commit(batch, ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sku", Integer.class));
        assertEquals("P90", jdbc.queryForObject(
                "SELECT model FROM sku WHERE customer_part_number='SXSEL_P90YZH70WPSE-A'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT actual_quantity FROM inventory_balance", Integer.class));
        assertEquals(120, jdbc.queryForObject("SELECT in_transit_quantity FROM inventory_balance", Integer.class));
        assertEquals(1, committed.committedRows());
        assertEquals(1, committed.ignoredRows());
    }

    @Test
    void inventoryImportCreatesLegacyProductCodeWhenSkuIsNew() {
        jdbc.execute("ALTER TABLE sku ALTER COLUMN product_code SET NOT NULL");
        long batch = repository.create(ImportType.INVENTORY, "inventory.xlsx", "hash-product-code", List.of(
                row(ImportRowStatus.VALID, Map.of(
                        "customerPartNumber", "G1TEST0001", "model", "测试配件",
                        "actualQuantity", 8, "lockedQuantity", 0, "inTransitQuantity", 0), null)));

        commitService.commit(batch, ImportConflictPolicy.UPSERT_KEEP_EXISTING_ON_BLANK);

        assertEquals("OLD_000001", jdbc.queryForObject(
                "SELECT product_code FROM sku WHERE customer_part_number='G1TEST0001'", String.class));
    }

    private ParsedImportRow row(ImportRowStatus status, Map<String, Object> data, String error) {
        return new ParsedImportRow("Sheet1", 1, status, data, error);
    }
}

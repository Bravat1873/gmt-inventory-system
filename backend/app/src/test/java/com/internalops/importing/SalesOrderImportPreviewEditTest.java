package com.internalops.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/sales-command-schema.sql"})
class SalesOrderImportPreviewEditTest {
    @Autowired ImportBatchRepository repository;
    @Autowired ImportPreviewService previewService;
    @Autowired ImportValidationService validationService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareProducts() {
        jdbc.update("UPDATE sku SET product_code=CONCAT('P', id)");
    }

    @Test
    void editingOneOrderLevelFieldRevalidatesAndRejectsTheWholeGroup() {
        long batchId = createTwoLineBatch();
        ImportBatchView before = repository.findBatch(batchId);
        ImportRowView second = before.rows().get(1);
        Map<String, Object> changed = new LinkedHashMap<>(second.data());
        changed.put("salesperson", "Another Salesperson");

        previewService.update(batchId, second.id(), new ImportRowRequest(changed));

        ImportBatchView batch = repository.findBatch(batchId);
        assertEquals(0, batch.validRows());
        assertEquals(2, batch.errorRows());
        assertTrue(batch.rows().stream().allMatch(row -> row.status() == ImportRowStatus.ERROR));
        assertTrue(batch.rows().stream().allMatch(row -> row.errorMessage().contains("订单级字段不一致：销售员")));
    }

    @Test
    void restoringOrderLevelConsistencyRevalidatesTheWholeGroupToValid() {
        long batchId = createTwoLineBatch();
        ImportRowView second = repository.findBatch(batchId).rows().get(1);
        Map<String, Object> changed = new LinkedHashMap<>(second.data());
        changed.put("salesperson", "Another Salesperson");
        previewService.update(batchId, second.id(), new ImportRowRequest(changed));

        Map<String, Object> restored = new LinkedHashMap<>(repository.findRow(batchId, second.id()).data());
        restored.put("salesperson", "Sales Zhang");
        previewService.update(batchId, second.id(), new ImportRowRequest(restored));

        ImportBatchView batch = repository.findBatch(batchId);
        assertEquals(2, batch.validRows());
        assertEquals(0, batch.errorRows());
        assertTrue(batch.rows().stream().allMatch(row -> row.status() == ImportRowStatus.VALID));
    }

    @Test
    void addingAnInconsistentOrderLineRevalidatesTheExistingGroup() {
        List<ParsedImportRow> rows = validationService.validateAll(ImportType.ORDER,
                List.of(parsed(2, order("P1", 1))));
        long batchId = repository.create(ImportType.ORDER, "orders.xlsx", "add-" + System.nanoTime(), rows);
        Map<String, Object> added = new LinkedHashMap<>(order("P2", 2));
        added.put("orderType", "零售订单");

        previewService.add(batchId, new ImportRowRequest(added));

        ImportBatchView batch = repository.findBatch(batchId);
        assertEquals(0, batch.validRows());
        assertEquals(2, batch.errorRows());
        assertTrue(batch.rows().stream().allMatch(row -> row.errorMessage().contains("订单级字段不一致：订单类型")));
    }

    @Test
    void serviceRejectsAddingAndUpdatingRowsAfterCommitHasStarted() {
        long batchId = createTwoLineBatch();
        ImportRowView existing = repository.findBatch(batchId).rows().get(0);
        repository.markCommitting(batchId);

        IllegalArgumentException addFailure = assertThrows(IllegalArgumentException.class,
                () -> previewService.add(batchId, new ImportRowRequest(order("P1", 1))));
        IllegalArgumentException updateFailure = assertThrows(IllegalArgumentException.class,
                () -> previewService.update(batchId, existing.id(), new ImportRowRequest(order("P1", 1))));

        assertEquals("仅预览中的批次可以修改", addFailure.getMessage());
        assertEquals("仅预览中的批次可以修改", updateFailure.getMessage());
        assertEquals(2, repository.findBatch(batchId).totalRows());
    }

    private long createTwoLineBatch() {
        List<ParsedImportRow> rows = validationService.validateAll(ImportType.ORDER, List.of(
                parsed(2, order("P1", 1)), parsed(3, order("P2", 2))));
        return repository.create(ImportType.ORDER, "orders.xlsx", "edit-" + System.nanoTime(), rows);
    }

    private ParsedImportRow parsed(int row, Map<String, Object> data) {
        return new ParsedImportRow("订单导入", row, ImportRowStatus.VALID, data, null);
    }

    private Map<String, Object> order(String productCode, int quantity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("externalOrderNo", "EXT-EDIT");
        data.put("customerCode", "C1");
        data.put("orderDate", "2026-08-17");
        data.put("orderType", "工程订单");
        data.put("orderStatus", "草稿");
        data.put("salesperson", "Sales Zhang");
        data.put("productCode", productCode);
        data.put("quantity", quantity);
        data.put("salePrice", new BigDecimal("12.50"));
        return data;
    }
}

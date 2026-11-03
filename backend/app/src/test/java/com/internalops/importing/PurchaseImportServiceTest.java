package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Sql(scripts = {"/import-schema.sql", "/procurement-workflow-schema.sql"})
class PurchaseImportServiceTest {
    @Autowired ImportPreviewService preview;
    @Autowired ImportCommitService commit;
    @Autowired ImportBatchRepository repository;
    @Autowired ImportValidationService validation;
    @Autowired JdbcTemplate jdbc;

    private ParsedImportRow row(int number, String group, String quantity, String price) {
        return new ParsedImportRow("采购导入", number, ImportRowStatus.VALID,
                new LinkedHashMap<>(Map.of("purchaseGroup", group, "supplierName", "供应商一", "productCode", "BR_P90",
                        "quantity", quantity, "purchasePrice", price, "expectedArrivalDate", "2026-12-01")), null);
    }
    private ParsedImportRow with(ParsedImportRow row, String key, String value) {
        var data = new LinkedHashMap<>(row.data());
        data.put(key, value);
        return new ParsedImportRow(row.sheetName(), row.rowNumber(), row.status(), data, row.errorMessage());
    }
    private long batch(List<ParsedImportRow> rows) {
        return repository.create(ImportType.PURCHASE, "purchase.xlsx", UUID.randomUUID().toString(), validation.validateAll(ImportType.PURCHASE, rows));
    }

    @Test void groupsDraftsAndCommitsIdempotentlyWithoutInventoryOrPayment() {
        long id = batch(List.of(row(2, "A", "10", "10"), row(3, "A", "20", "10"), row(4, "B", "10", "10")));
        assertEquals(3, commit.commit(id).committedRows());
        assertEquals(3, commit.commit(id).committedRows());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE status='DRAFT'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_item", Integer.class));
        assertEquals(new BigDecimal("400.00"), jdbc.queryForObject("SELECT SUM(total_amount) FROM purchase_order", BigDecimal.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_payment", Integer.class));
    }

    @Test void blankGroupsCreateSeparateOrders() {
        long id = batch(List.of(row(2, "", "10", "10"), row(3, "", "10", "10")));
        commit.commit(id);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class));
    }

    @Test void validatesQuantityPriceDateAndGroupHeaders() {
        var rows = validation.validateAll(ImportType.PURCHASE, List.of(row(2, "A", "9", "10"), row(3, "B", "1.5", "10"), row(4, "C", "10", "11")));
        assertTrue(rows.stream().allMatch(r -> r.status() == ImportRowStatus.ERROR));
        var first = row(2, "A", "10", "10");
        var second = row(3, "A", "10", "10");
        second = with(second, "expectedArrivalDate", "2026-12-02");
        assertTrue(validation.validateAll(ImportType.PURCHASE, List.of(first, second)).stream().allMatch(r -> r.status() == ImportRowStatus.ERROR));
        second = with(second, "expectedArrivalDate", "2026-02-30");
        assertEquals(ImportRowStatus.ERROR, validation.validateAll(ImportType.PURCHASE, List.of(second)).get(0).status());
    }

    @Test void validatesRemarkLengthAgainstPurchaseSchema() {
        var row = with(row(2, "A", "10", "10"), "remark", "字".repeat(501));
        assertEquals(ImportRowStatus.ERROR, validation.validateAll(ImportType.PURCHASE, List.of(row)).get(0).status());
        row = with(row, "remark", "字".repeat(500));
        assertEquals(ImportRowStatus.VALID, validation.validateAll(ImportType.PURCHASE, List.of(row)).get(0).status());
    }

    @Test void blocksChangedQuotesAndInvalidRowsWithoutCreatingOrders() {
        long id = batch(List.of(row(2, "A", "10", "10")));
        jdbc.update("UPDATE sku_supplier_purchase_info SET purchase_price=11 WHERE id=1");
        assertThrows(IllegalArgumentException.class, () -> commit.commit(id));
        assertEquals("PREVIEW", repository.findBatch(id).status());
        long invalid = batch(List.of(row(2, "A", "10", "11"), row(3, "B", "0", "11")));
        assertThrows(IllegalArgumentException.class, () -> commit.commit(invalid));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class));
    }

    @Test void rollsBackEarlierGroupsWhenLaterTotalExceedsLimit() {
        jdbc.update("UPDATE sku_supplier_purchase_info SET purchase_price=99999999999999 WHERE id=1");
        long id = batch(List.of(row(2, "A", "10", "99999999999999"), row(3, "B", "2147483647", "99999999999999")));
        assertThrows(IllegalArgumentException.class, () -> commit.commit(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order", Integer.class));
        assertEquals("PREVIEW", repository.findBatch(id).status());
    }

    @Test void rejectsAmbiguousQuotesUnlessIdProvided() {
        jdbc.update("INSERT INTO sku_supplier_purchase_info(id,supplier_product_config_id,purchase_price,moq,lead_time_days,enabled) VALUES(2,1,10,10,7,TRUE)");
        var row = row(2, "A", "10", "10");
        assertEquals(ImportRowStatus.ERROR, validation.validateAll(ImportType.PURCHASE, List.of(row)).get(0).status());
        row = with(row, "purchaseInfoId", "2");
        assertEquals(ImportRowStatus.VALID, validation.validateAll(ImportType.PURCHASE, List.of(row)).get(0).status());
    }

    @Test void generatedTemplateParsesUploadedRowsAndExcelDates() throws Exception {
        byte[] template = new ImportTemplateWorkbookService().create(ImportType.PURCHASE);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(template)); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var row = sheet.createRow(1);
            String[] values = {"A", "供应商一", "BR_P90", "P90-001", "10", "10", "1"};
            for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
            var date = row.createCell(7);
            date.setCellValue(java.time.LocalDate.of(2026, 12, 1));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            workbook.write(output);
            var batch = preview.preview(ImportType.PURCHASE, new MockMultipartFile("file", "purchase.xlsx", "application/octet-stream", output.toByteArray()));
            assertEquals(1, batch.validRows());
            assertEquals("2026-12-01", batch.rows().get(0).data().get("expectedArrivalDate"));
            assertEquals(1, commit.commit(batch.batchId()).committedRows());
        }
    }
}

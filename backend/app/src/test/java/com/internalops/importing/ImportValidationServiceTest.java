package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportValidationServiceTest {
    private final ImportValidationService service = new ImportValidationService(new JdbcTemplate());

    @Test
    void acceptsLockedStockCoveredByTransitStock() {
        ParsedImportRow row = service.validate(ImportType.INVENTORY, "爱迪生", 37, Map.of(
                "skuCode", "SKU-1",
                "actualQuantity", 0,
                "lockedQuantity", 135,
                "inTransitQuantity", 135
        ));

        assertEquals(ImportRowStatus.VALID, row.status());
    }

    @Test
    void acceptsCostRowWithoutUniqueInventorySkuForAutomaticTemporaryCode() {
        ParsedImportRow row = service.validate(ImportType.COST, "Sheet1", 3, Map.of(
                "skuCode", "",
                "model", "P90",
                "color", "宇宙黑",
                "lockBody", "7068",
                "configuration", "可视对讲智能锁",
                "cost", "465"
        ));

        assertEquals(ImportRowStatus.VALID, row.status());
        assertTrue(Boolean.TRUE.equals(row.data().get("_autoSku")));
    }

    @Test
    void keepsFirstInventoryRowAndIgnoresLaterDuplicateSku() {
        List<ParsedImportRow> rows = service.validateAll(ImportType.INVENTORY, List.of(
                inventoryRow(19, " SXSEL_P90YZH70WPSE-A ", "P90", 120, 0, 120),
                inventoryRow(21, "sxsel_p90yzh70wpse-a", "P90装饰锁", 10, 0, 10)
        ));

        assertEquals(ImportRowStatus.VALID, rows.get(0).status());
        assertEquals("SXSEL_P90YZH70WPSE-A", rows.get(0).data().get("skuCode"));
        assertEquals("P90", rows.get(0).data().get("model"));
        assertEquals(120, rows.get(0).data().get("inTransitQuantity"));
        assertEquals(ImportRowStatus.IGNORED, rows.get(1).status());
        assertTrue(rows.get(1).errorMessage().contains("19"));
    }

    @Test
    void doesNotReplaceInvalidFirstInventoryRowWithLaterDuplicate() {
        List<ParsedImportRow> rows = service.validateAll(ImportType.INVENTORY, List.of(
                inventoryRow(19, "SKU-DUP", "first", -1, 0, 0),
                inventoryRow(21, "sku-dup", "second", 10, 0, 0)
        ));

        assertEquals(ImportRowStatus.ERROR, rows.get(0).status());
        assertEquals(ImportRowStatus.IGNORED, rows.get(1).status());
        assertTrue(rows.get(1).errorMessage().contains("19"));
    }

    private ParsedImportRow inventoryRow(int rowNumber, String skuCode, String model,
                                         int actual, int locked, int transit) {
        return new ParsedImportRow("爱迪生", rowNumber, ImportRowStatus.VALID, Map.of(
                "skuCode", skuCode,
                "model", model,
                "actualQuantity", actual,
                "lockedQuantity", locked,
                "inTransitQuantity", transit
        ), null);
    }
}

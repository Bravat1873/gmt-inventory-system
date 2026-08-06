package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
}

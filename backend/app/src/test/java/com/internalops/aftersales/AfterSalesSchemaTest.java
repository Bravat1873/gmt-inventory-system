package com.internalops.aftersales;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterSalesSchemaTest {
    @Test
    void migrationDefinesTraceableAfterSalesTablesAndConstraints() throws Exception {
        String sql = new ClassPathResource("db/migration/V32__after_sales_management.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"after_sales_order", "after_sales_return_line", "after_sales_replacement_line", "after_sales_event"}) {
            assertTrue(sql.contains("CREATE TABLE " + table), "缺少售后表 " + table);
        }
        assertTrue(sql.contains("CONSTRAINT uk_after_sales_no UNIQUE (after_sales_no)"));
        assertTrue(sql.contains("CHECK (requested_quantity > 0)"));
        assertTrue(sql.contains("INDEX idx_after_sales_event_order_time"));
    }
}

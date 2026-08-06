package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSchemaTest {
    @Test
    void migrationAddsUserPhoneAndWorkbenchIndexes() throws Exception {
        String sql = new ClassPathResource("db/migration/V4__business_workbench.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("ADD COLUMN phone VARCHAR(40)"));
        for (String index : new String[]{
                "idx_customer_name_enabled", "idx_sku_model_enabled", "idx_sku_name_enabled",
                "idx_sales_status_created", "idx_sales_customer_created", "idx_sales_fifo",
                "idx_balance_updated_sku", "idx_suggestion_status_created",
                "idx_purchase_status_created", "idx_purchase_supplier_created",
                "idx_receipt_time_order", "idx_payment_time_order"
        }) {
            assertTrue(sql.contains(index), "缺少索引 " + index);
        }
    }
}

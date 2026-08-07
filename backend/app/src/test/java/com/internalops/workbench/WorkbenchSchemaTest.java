package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchSchemaTest {
    @Test
    void partialPaymentMigrationCreatesForeignKeyIndexBeforeDroppingUniqueIndex() throws Exception {
        String sql = new ClassPathResource("db/migration/V19__partial_procurement_payments.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        int createIndex = sql.indexOf("CREATE INDEX idx_supplier_payment_purchase");
        int dropUnique = sql.indexOf("DROP INDEX uk_supplier_payment_order");
        assertTrue(createIndex >= 0 && createIndex < dropUnique,
                "MySQL 外键需要先有普通索引，才能删除原唯一索引");
    }

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

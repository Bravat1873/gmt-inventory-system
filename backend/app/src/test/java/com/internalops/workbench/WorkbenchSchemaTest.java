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

    @Test
    void shipmentMigrationRequiresAnOperatorName() throws Exception {
        String sql = new ClassPathResource("db/migration/V22__shipment_batches.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("operator_name VARCHAR(100) NOT NULL"),
                "发货批次必须持久化非空操作人名称");
    }

    @Test
    void orderContactSnapshotMigrationAddsAllSixNullableColumns() throws Exception {
        String sql = new ClassPathResource("db/migration/V23__sales_order_contact_snapshots.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        for (String column : new String[]{
                "business_contact_name VARCHAR(100) NULL",
                "business_contact_phone VARCHAR(60) NULL",
                "order_contact_name VARCHAR(100) NULL",
                "order_contact_phone VARCHAR(60) NULL",
                "finance_contact_name VARCHAR(100) NULL",
                "finance_contact_phone VARCHAR(60) NULL"
        }) {
            assertTrue(sql.contains(column), "订单联系人快照迁移缺少 " + column);
        }
    }

    @Test
    void plaintextPasswordMigrationResetsEveryExistingUserTo123() throws Exception {
        String sql = new ClassPathResource("db/migration/V25__plaintext_user_passwords.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("UPDATE sys_user SET password_hash = '123'"),
                "V25 必须把所有现有账号密码统一重置为明文 123");
    }

    @Test
    void supplierPurchaseInfoMigrationKeepsQuoteHistoryAndReferencesItsSource() throws Exception {
        String sql = new ClassPathResource("db/migration/V40__supplier_purchase_info_history.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        for (String fragment : new String[]{
                "CREATE TABLE sku_supplier_purchase_info",
                "supplier_product_config_id BIGINT NOT NULL",
                "purchase_price DECIMAL(18,4) NOT NULL",
                "moq INT NOT NULL",
                "lead_time_days INT NOT NULL",
                "enabled BOOLEAN NOT NULL DEFAULT TRUE",
                "version INT NOT NULL DEFAULT 0",
                "INSERT INTO sku_supplier_purchase_info",
                "FROM sku_supplier_config",
                "ALTER TABLE purchase_order_item",
                "ALTER TABLE procurement_suggestion_item",
                "supplier_purchase_info_id BIGINT NULL"
        }) {
            assertTrue(sql.contains(fragment), "采购信息历史迁移缺少 " + fragment);
        }
    }}

package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCodeSchemaTest {
    private String migration() throws Exception {
        return new ClassPathResource("db/migration/V28__product_code_rules.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void createsRulesAndMakesCustomerCodeNullableAndNonUnique() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("CREATE TABLE product_code_rule"));
        assertTrue(sql.contains("DROP INDEX uk_sku_code"));
        assertTrue(sql.contains("MODIFY COLUMN sku_code VARCHAR(80) NULL"));
        assertTrue(sql.contains("CONSTRAINT uk_product_code_rule_category_code UNIQUE (category, code)"));
    }

    @Test
    void addsUniqueProductCodeAndEightRuleReferences() throws Exception {
        String sql = migration();
        assertTrue(sql.contains("product_code VARCHAR(80)"));
        assertTrue(sql.contains("CONCAT('OLD_', LPAD(id, 6, '0'))"));
        assertTrue(sql.contains("CONSTRAINT uk_sku_product_code UNIQUE (product_code)"));
        for (String column : new String[]{"brand_rule_id", "series_rule_id", "body_color_rule_id",
                "lock_type_rule_id", "connectivity_rule_id", "sales_channel_rule_id",
                "operating_entity_rule_id", "language_rule_id"}) {
            assertTrue(sql.contains(column + " BIGINT NULL"), "missing " + column);
        }
    }

    @Test
    void seedsTheApprovedMappings() throws Exception {
        String sql = migration();
        for (String seed : new String[]{"'BR','STANLEY'", "'HGT','红钻铜'", "'60','6068'", "'W','WiFi'"}) {
            assertTrue(sql.contains(seed), "missing seed " + seed);
        }
    }
}

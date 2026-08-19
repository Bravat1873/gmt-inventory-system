package com.internalops.workbench;

import com.internalops.productcode.ProductCodeCategory;
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
        assertTrue(sql.contains("DROP INDEX uk_customer_part_number"));
        assertTrue(sql.contains("MODIFY COLUMN customer_part_number VARCHAR(80) NULL"));
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

    @Test
    void correctsTheThreeApprovedBrandMappingsWithoutReplacingReferencedRules() throws Exception {
        String sql = new ClassPathResource("db/migration/V33__correct_product_brand_rules.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        for (String mapping : new String[]{"'SXSEL', 'STANLEY'", "'BR', 'BRAVAT'", "'G', 'GMT'"}) {
            assertTrue(sql.contains(mapping), "missing brand mapping " + mapping);
        }
        assertTrue(sql.contains("UPDATE product_code_rule"));
        assertTrue(sql.contains("WHERE category = 'BRAND' AND display_name = 'STANLEY'"));
    }

    @Test
    void productConfigurationMigrationAddsAnIndependentEditableColumn() throws Exception {
        String sql = new ClassPathResource("db/migration/V34__product_configuration.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("ADD COLUMN product_configuration VARCHAR(500) NULL"));
    }

    @Test
    void productCodeSuffixMigrationAddsTextColumnAndRuleCategory() throws Exception {
        String sql = new ClassPathResource("db/migration/V36__product_code_suffix.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("ADD COLUMN code_suffix TEXT NULL"));
        assertTrue(ProductCodeCategory.valueOf("SUFFIX") == ProductCodeCategory.valueOf("SUFFIX"));
    }

    void entryDoorMigrationAddsProductTypeFiveReferencesAndSeeds() throws Exception {
        String sql = new ClassPathResource("db/migration/V29__entry_door_product_codes.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("product_type VARCHAR(30) NOT NULL DEFAULT 'UNCLASSIFIED'"));
        for (String column : new String[]{"door_model_rule_id", "security_grade_rule_id", "base_material_rule_id", "thickness_rule_id", "finish_color_rule_id"}) {
            assertTrue(sql.contains(column + " BIGINT NULL"), "missing " + column);
        }
        for (String seed : new String[]{"'DOOR_MODEL','M','单开门'", "'SECURITY_GRADE','J','甲'", "'BASE_MATERIAL','3','304不锈钢'", "'THICKNESS','080','80mm'", "'FINISH_COLOR','A','花色A'"}) {
            assertTrue(sql.contains(seed), "missing seed " + seed);
        }
    }}

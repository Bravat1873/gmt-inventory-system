package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerPartNumberMigrationTest {
    @Test
    void renamesEveryProductCustomerPartNumberColumnWithoutCompatibilityColumns() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V47__rename_sku_code_to_customer_part_number.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        String oldColumn = "sku" + "_code";
        assertTrue(sql.contains("ALTER TABLE sku RENAME COLUMN " + oldColumn + " TO customer_part_number"));
        assertTrue(sql.contains("ALTER TABLE after_sales_return_line RENAME COLUMN " + oldColumn + " TO customer_part_number"));
        assertTrue(sql.contains("ALTER TABLE after_sales_replacement_line RENAME COLUMN " + oldColumn + " TO customer_part_number"));
        assertFalse(sql.contains("ADD COLUMN " + oldColumn));
    }
}

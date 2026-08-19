package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductConfigurationEncodingMigrationTest {
    @Test
    void repairsOnlyTheKnownDecorativeLockMojibakeInMaterialSpecifications() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V49__repair_decorative_lock_configuration_encoding.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("UPDATE product_code_rule"));
        assertTrue(sql.contains("REPLACE(display_name"));
        assertTrue(sql.contains("UPDATE sku"));
        assertTrue(sql.contains("REPLACE(configuration"));
        assertTrue(sql.contains("REPLACE(product_configuration"));
        assertTrue(sql.contains("装饰锁"));
    }
}

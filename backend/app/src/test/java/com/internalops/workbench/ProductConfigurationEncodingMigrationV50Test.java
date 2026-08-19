package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductConfigurationEncodingMigrationV50Test {
    @Test
    void repairsRemainingDecorativeLockMojibakeInProductConfiguration() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V50__repair_decorative_lock_configuration_encoding_remaining.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("UPDATE sku"));
        assertTrue(sql.contains("REPLACE("));
        assertTrue(sql.contains("configuration"));
        assertTrue(sql.contains("HEX(configuration)"));
        assertTrue(sql.contains("C3A8C2A3E280A6C3A9C2A5C2B0C3A9E2809DC281"));
        assertTrue(sql.contains("装饰锁"));
    }
}

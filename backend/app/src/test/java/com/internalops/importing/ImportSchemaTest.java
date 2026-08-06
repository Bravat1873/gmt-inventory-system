package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Sql(scripts = "/import-schema.sql")
class ImportSchemaTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void importStagingTablesExist() {
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class);
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM import_row", Integer.class);
        assertEquals(0, batches);
        assertEquals(0, rows);
    }
}

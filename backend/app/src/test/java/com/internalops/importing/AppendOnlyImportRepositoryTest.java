package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Sql("/import-schema.sql")
class AppendOnlyImportRepositoryTest {
    @Autowired ImportBatchRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsDistinctBatchesForTheSameFileAndKeepsDuplicateBusinessValues() {
        RawWorkbookSnapshot snapshot = new RawWorkbookSnapshot(new byte[]{1, 2}, List.of(
                new RawWorkbookSheet(0, "库存", 2, List.of(
                        new RawWorkbookRow(1, List.of("SKU", "SKU")),
                        new RawWorkbookRow(2, List.of("SKU-001", "SKU-001"))))));

        long first = repository.createAppendOnly(ImportType.INVENTORY, "库存.xlsx", snapshot);
        long second = repository.createAppendOnly(ImportType.INVENTORY, "库存.xlsx", snapshot);

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_workbook_row", Integer.class)).isEqualTo(4);
        assertThat(repository.findBatch(first).totalRows()).isEqualTo(2);
        assertThat(repository.findBatch(second).validRows()).isEqualTo(2);
    }
}

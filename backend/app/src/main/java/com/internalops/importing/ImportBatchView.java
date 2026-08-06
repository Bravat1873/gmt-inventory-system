package com.internalops.importing;

import java.util.List;

public record ImportBatchView(
        long batchId,
        ImportType importType,
        String originalFilename,
        String status,
        int totalRows,
        int validRows,
        int errorRows,
        int ignoredRows,
        int committedRows,
        Object result,
        List<ImportRowView> rows
) {
    public ImportBatchView {
        rows = List.copyOf(rows);
    }
}

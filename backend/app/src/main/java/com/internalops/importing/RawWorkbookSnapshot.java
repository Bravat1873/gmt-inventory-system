package com.internalops.importing;

import java.util.List;

record RawWorkbookSnapshot(byte[] fileContent, List<RawWorkbookSheet> sheets) {
    RawWorkbookSnapshot {
        fileContent = fileContent.clone();
        sheets = List.copyOf(sheets);
    }
}

record RawWorkbookSheet(int sheetIndex, String sheetName, int columnCount, List<RawWorkbookRow> rows) {
    RawWorkbookSheet {
        rows = List.copyOf(rows);
    }
}

record RawWorkbookRow(int sourceRow, List<String> cells) {
    RawWorkbookRow {
        cells = List.copyOf(cells);
    }
}

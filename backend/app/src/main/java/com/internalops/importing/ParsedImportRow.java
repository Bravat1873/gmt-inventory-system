package com.internalops.importing;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record ParsedImportRow(
        String sheetName,
        int rowNumber,
        ImportRowStatus status,
        Map<String, Object> data,
        String errorMessage
) {
    public ParsedImportRow {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}

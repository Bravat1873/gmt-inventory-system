package com.internalops.importing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ImportRowView(
        long id,
        String sheetName,
        int rowNumber,
        ImportRowStatus status,
        Map<String, Object> data,
        String errorMessage,
        boolean manualEntry
) {
    public ImportRowView {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}

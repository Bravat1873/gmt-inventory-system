package com.internalops.importing;

import java.util.Map;

public record ImportRowRequest(Map<String, Object> data) {
    public ImportRowRequest {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}

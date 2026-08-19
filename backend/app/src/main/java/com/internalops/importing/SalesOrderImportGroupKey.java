package com.internalops.importing;

import java.util.Map;

final class SalesOrderImportGroupKey {
    private SalesOrderImportGroupKey() { }

    static String from(Map<String, Object> data) {
        String externalOrderNo = text(data, "externalOrderNo");
        if (!externalOrderNo.isBlank()) return externalOrderNo;
        return "AUTO|" + normalized(data, "customerCode") + "|"
                + normalized(data, "orderDate") + "|" + normalized(data, "orderType");
    }

    private static String normalized(Map<String, Object> data, String key) {
        return text(data, key).replaceAll("\\s+", " ");
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
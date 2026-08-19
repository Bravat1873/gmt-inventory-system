package com.internalops.productcode;

import java.util.Locale;

public final class ProductUniqueId {
    private ProductUniqueId() {
    }

    public static String from(String productCode, String customerPartNumber) {
        String code = normalize(productCode);
        String partNumber = normalize(customerPartNumber);
        if (code.isBlank() || partNumber.isBlank()) {
            throw new IllegalArgumentException("产品编号和客户料号不能为空");
        }
        return code + "::" + partNumber;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

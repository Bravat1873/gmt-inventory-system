package com.internalops.workbench;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record EntityCommandFields(
        boolean currentCostPresent,
        boolean factoryPricePresent,
        boolean rolePresent,
        Map<String, String> supplierText) {
    private static final Set<String> SUPPLIER_TEXT_FIELDS = Set.of(
            "supplierCode", "supplierName", "manufacturerCategory", "manufacturerType",
            "supplierLocation", "productAttribute", "shortName", "contactTitle",
            "address", "currency", "taxRegistrationNo", "bankAccount", "bankAddress");

    public static EntityCommandFields from(JsonNode body) {
        return new EntityCommandFields(
                body.has("currentCost"),
                body.has("factoryPrice"),
                body.has("role"),
                supplierText(body));
    }

    public static EntityCommandFields inferred(EntityCommandRequest request) {
        return new EntityCommandFields(
                request.currentCost() != null,
                request.factoryPrice() != null,
                request.role() != null,
                Map.of());
    }

    public String supplierText(String field) {
        if (!SUPPLIER_TEXT_FIELDS.contains(field)) {
            throw new IllegalArgumentException("不支持的供应商字段");
        }
        return supplierText.get(field);
    }

    private static Map<String, String> supplierText(JsonNode body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : SUPPLIER_TEXT_FIELDS) {
            if (body.has(field)) {
                JsonNode value = body.get(field);
                values.put(field, value == null || value.isNull() ? null : value.asText());
            }
        }
        return Collections.unmodifiableMap(values);
    }
}

package com.internalops.workbench;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record EntityCommandFields(
        boolean currentCostPresent,
        boolean factoryPricePresent,
        boolean rolePresent,
        boolean passwordPresent,
        boolean lockedAllocationsPresent,
        List<InventoryLockedAllocationCommand> lockedAllocations,
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
                body.has("password"),
                body.has("lockedAllocations"),
                lockedAllocations(body),
                supplierText(body));
    }

    public static EntityCommandFields inferred(EntityCommandRequest request) {
        return new EntityCommandFields(
                request.currentCost() != null,
                request.factoryPrice() != null,
                request.role() != null,
                request.password() != null,
                false,
                List.of(),
                Map.of());
    }

    public String supplierText(String field) {
        if (!SUPPLIER_TEXT_FIELDS.contains(field)) {
            throw new IllegalArgumentException("不支持的供应商字段");
        }
        return supplierText.get(field);
    }

    private static List<InventoryLockedAllocationCommand> lockedAllocations(JsonNode body) {
        JsonNode values = body.get("lockedAllocations");
        if (values == null || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalArgumentException("地点锁定分配格式不正确");
        List<InventoryLockedAllocationCommand> result = new ArrayList<>();
        for (JsonNode value : values) {
            String source = value.hasNonNull("lockSource") ? value.get("lockSource").asText() : null;
            Integer quantity = value.hasNonNull("quantity") && value.get("quantity").canConvertToInt()
                    ? value.get("quantity").intValue() : null;
            result.add(new InventoryLockedAllocationCommand(source, quantity));
        }
        return List.copyOf(result);
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

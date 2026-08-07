package com.internalops.workbench;

import com.fasterxml.jackson.databind.JsonNode;

public record EntityCommandFields(
        boolean currentCostPresent,
        boolean factoryPricePresent,
        boolean rolePresent) {

    public static EntityCommandFields from(JsonNode body) {
        return new EntityCommandFields(
                body.has("currentCost"),
                body.has("factoryPrice"),
                body.has("role"));
    }

    public static EntityCommandFields inferred(EntityCommandRequest request) {
        return new EntityCommandFields(
                request.currentCost() != null,
                request.factoryPrice() != null,
                request.role() != null);
    }
}

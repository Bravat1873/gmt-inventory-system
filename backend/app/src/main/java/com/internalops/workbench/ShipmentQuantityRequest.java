package com.internalops.workbench;

import java.util.List;

public record ShipmentQuantityRequest(List<Item> items) {
    public record Item(Integer lineNo, Integer shippedQuantity) {}
}

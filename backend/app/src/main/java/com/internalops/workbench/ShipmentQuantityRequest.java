package com.internalops.workbench;

import java.util.List;

public record ShipmentQuantityRequest(String deliveryAddress, String remark, List<Item> items) {
    public ShipmentQuantityRequest(List<Item> items) {
        this(null, null, items);
    }

    public record Item(Integer lineNo, Integer shippedQuantity) {}
}

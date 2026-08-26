package com.internalops.workbench;

import java.util.List;

public record ShipmentQuantityRequest(String deliveryAddress, String remark, String logisticsCompany, String logisticsNo,
                                      String logisticsRemark, List<Item> items) {
    public ShipmentQuantityRequest(String deliveryAddress, String remark, List<Item> items) {
        this(deliveryAddress, remark, null, null, null, items);
    }

    public ShipmentQuantityRequest(List<Item> items) {
        this(null, null, null, null, null, items);
    }

    public record Item(Integer lineNo, Integer shippedQuantity) {}
}

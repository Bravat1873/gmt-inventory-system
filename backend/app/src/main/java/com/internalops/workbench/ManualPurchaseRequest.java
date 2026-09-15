package com.internalops.workbench;

import java.time.LocalDate;
import java.util.List;

public record ManualPurchaseRequest(
        long supplierId,
        long skuId,
        long supplierPurchaseInfoId,
        int quantity,
        LocalDate expectedArrivalDate,
        String deliveryAddress,
        String remark,
        List<Item> items) {
    public ManualPurchaseRequest(long supplierId, long skuId, long supplierPurchaseInfoId, int quantity,
                                 LocalDate expectedArrivalDate, String deliveryAddress, String remark) {
        this(supplierId, skuId, supplierPurchaseInfoId, quantity, expectedArrivalDate, deliveryAddress, remark, null);
    }

    public List<Item> purchaseItems() {
        return items == null ? List.of(new Item(skuId, supplierPurchaseInfoId, quantity)) : items;
    }

    public record Item(long skuId, long supplierPurchaseInfoId, int quantity) {}
}

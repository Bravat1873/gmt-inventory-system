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
        List<Item> items, Integer version) {
    public ManualPurchaseRequest(long supplierId, long skuId, long supplierPurchaseInfoId, int quantity,
                                 LocalDate expectedArrivalDate, String deliveryAddress, String remark) {
        this(supplierId, skuId, supplierPurchaseInfoId, quantity, expectedArrivalDate, deliveryAddress, remark, null, null);
    }

    public List<Item> purchaseItems() {
        return items == null ? List.of(new Item(skuId, supplierPurchaseInfoId, quantity)) : items;
    }

    public record Item(long skuId, long supplierPurchaseInfoId, int quantity, Long id, Boolean retainPrice) {
        public Item(long skuId, long supplierPurchaseInfoId, int quantity) { this(skuId, supplierPurchaseInfoId, quantity, null, null); }
    }
}

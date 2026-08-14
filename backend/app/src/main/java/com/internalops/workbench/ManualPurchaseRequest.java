package com.internalops.workbench;

import java.time.LocalDate;

public record ManualPurchaseRequest(
        long supplierId,
        long skuId,
        long supplierPurchaseInfoId,
        int quantity,
        LocalDate expectedArrivalDate,
        String remark) {
}
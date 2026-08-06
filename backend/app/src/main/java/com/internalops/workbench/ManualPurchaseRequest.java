package com.internalops.workbench;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ManualPurchaseRequest(
        long supplierId,
        long skuId,
        int quantity,
        BigDecimal purchasePrice,
        LocalDate expectedArrivalDate,
        String remark) {
}

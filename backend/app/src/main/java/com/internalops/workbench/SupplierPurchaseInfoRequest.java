package com.internalops.workbench;

import java.math.BigDecimal;

public record SupplierPurchaseInfoRequest(
        Long id,
        BigDecimal purchasePrice,
        Integer moq,
        Integer leadTimeDays,
        Integer version) {
}
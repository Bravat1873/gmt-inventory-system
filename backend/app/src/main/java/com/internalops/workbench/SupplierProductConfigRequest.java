package com.internalops.workbench;

import java.math.BigDecimal;

public record SupplierProductConfigRequest(
        Long skuId,
        BigDecimal purchasePrice,
        Integer moq,
        Integer leadTimeDays) {
}

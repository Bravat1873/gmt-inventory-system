package com.internalops.workbench;

import java.math.BigDecimal;

public record ProductSupplierQuoteCommand(
        Long supplierId,
        BigDecimal purchasePrice,
        Integer moq,
        Integer leadTimeDays) {
}

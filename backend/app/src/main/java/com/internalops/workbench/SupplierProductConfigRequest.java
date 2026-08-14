package com.internalops.workbench;

import java.math.BigDecimal;
import java.util.List;

public record SupplierProductConfigRequest(
        Long skuId,
        BigDecimal purchasePrice,
        Integer moq,
        Integer leadTimeDays,
        List<SupplierPurchaseInfoRequest> purchaseInfos) {

    public List<SupplierPurchaseInfoRequest> effectivePurchaseInfos() {
        if (purchaseInfos != null) return purchaseInfos;
        if (purchasePrice == null && moq == null && leadTimeDays == null) return List.of();
        return List.of(new SupplierPurchaseInfoRequest(null, purchasePrice, moq, leadTimeDays, null));
    }
}
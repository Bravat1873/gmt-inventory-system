package com.internalops.workbench;

import java.math.BigDecimal;
import java.util.List;

public record EntityCommandRequest(
        String customerCode, String customerName, String contactName, String phone, String address,
        String username, String displayName, Boolean enabled,
        String skuCode, String model, String productName, String color, String lockBody,
        String productVersion, String configuration, String unit, BigDecimal currentCost, BigDecimal factoryPrice, String remark,
        Long supplierId, BigDecimal purchasePrice, Integer moq, Integer leadTimeDays,
        Long skuId, Integer actualQuantity, Integer lockedQuantity, Integer inTransitQuantity,
        Integer lockedMingAiJunQiao, Integer lockedBoLeLongMi, Integer lockedLaos,
        Integer lockedBeiLang, Integer lockedMalaysia,
        String movementDate, Integer inboundQuantity, Integer outboundQuantity,
        List<InventoryMovementCommand> inventoryMovements,
        String reason, String sourceSupplierName, String inventoryRemark, Integer version) {
}

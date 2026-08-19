package com.internalops.workbench;

import java.math.BigDecimal;
import java.util.List;

public record EntityCommandRequest(
        String customerCode, String customerName, String contactName, String phone, String address,
        String businessContactName, String businessContactPhone, String orderContactName, String orderContactPhone,
        String financeContactName, String financeContactPhone, String invoiceTitle, String taxpayerId,
        String invoiceAddress, String invoicePhone, String bankName, String bankAccount,
        String username, String displayName, String password, String role, Boolean enabled,
        String customerPartNumber, String productCode, String codeSuffix, String eanCode, String model, String productName, String color, String lockBody,
        String productVersion, String configuration, String productConfiguration, String unit, Integer salesMinimumOrderQuantity, BigDecimal currentCost, BigDecimal factoryPrice, String remark,
        Long brandRuleId, Long seriesRuleId, Long bodyColorRuleId, Long lockTypeRuleId,
        Long connectivityRuleId, Long salesChannelRuleId, Long operatingEntityRuleId, Long languageRuleId,
        String productType, String materialType, Long doorModelRuleId, Long securityGradeRuleId, Long baseMaterialRuleId,
        Long thicknessRuleId, Long finishColorRuleId,
        Long supplierId, BigDecimal purchasePrice, Integer moq, Integer leadTimeDays,
        Long skuId, Integer actualQuantity, Integer availableQuantity, Integer lockedQuantity, Integer inTransitQuantity,
        Integer lockedMingAiJunQiao, Integer lockedBoLeLongMi, Integer lockedLaos,
        Integer lockedBeiLang, Integer lockedMalaysia,
        String movementDate, Integer inboundQuantity, Integer outboundQuantity,
        List<InventoryMovementCommand> inventoryMovements,
        String reason, String sourceSupplierName, String inventoryRemark, Integer version) {
}

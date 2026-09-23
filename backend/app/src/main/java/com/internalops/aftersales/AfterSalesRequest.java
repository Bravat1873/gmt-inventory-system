package com.internalops.aftersales;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

public record AfterSalesRequest(long orderId, LocalDate applicationDate, String issueDescription,
                                String contactName, String contactPhone, String deliveryAddress, String remark,
                                Integer version, List<ReturnLine> returnLines, List<ReplacementLine> replacementLines) {
    public record ReturnLine(long salesOrderItemId, int requestedQuantity, BigDecimal returnUnitPrice) {}
    public record ReplacementLine(Long returnLineId, Long salesOrderItemId, long skuId, int plannedQuantity) {}
}

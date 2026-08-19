package com.internalops.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DashboardSnapshot(
        long todayOrders, BigDecimal todaySalesAmount, long pendingReceipt, long pendingStock,
        long pendingShipment, long shortageProducts, long shortageQuantity,
        long pendingPurchasePayment, long activeAfterSales, int days,
        long actualInventory, long lockedInventory, long inTransitInventory, long pendingDeliveryQuantity, long supplyDemandSurplus,
        List<TrendPoint> trend, List<ProductException> exceptions, Instant generatedAt) {
    public record TrendPoint(LocalDate date, long orderCount, BigDecimal salesAmount,
                             long purchaseQuantity, long shipmentQuantity, long afterSalesCount) {}
    public record ProductException(long productId, String category, String customerPartNumber,
                                   String model, String productCode, long actualQuantity,
                                   long inTransitQuantity, long pendingDeliveryQuantity,
                                   long supplyDemandSurplus, long shortageQuantity,
                                   long relatedBusinessCount) {}
}






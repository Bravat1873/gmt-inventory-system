package com.internalops.dashboard;

public record DashboardSummary(
        long pendingReceipt,
        long pendingStock,
        long pendingPurchasePayment,
        long pendingShipment) {
}

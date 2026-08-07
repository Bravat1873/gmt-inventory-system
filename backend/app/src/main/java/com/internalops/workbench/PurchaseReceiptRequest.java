package com.internalops.workbench;

import java.util.List;

public record PurchaseReceiptRequest(List<Item> items) {
    public record Item(long purchaseOrderItemId, Integer receivedQuantity) {}
}

package com.internalops.aftersales;

import java.time.LocalDate;
import java.util.List;

public record AfterSalesReceiptRequest(LocalDate receiptDate, int version, List<Item> items) {
    public record Item(long returnLineId, int goodQuantity, int defectiveQuantity) {}
}

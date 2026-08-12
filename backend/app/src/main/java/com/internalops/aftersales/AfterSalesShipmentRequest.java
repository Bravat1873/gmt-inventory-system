package com.internalops.aftersales;

import java.time.LocalDate;
import java.util.List;

public record AfterSalesShipmentRequest(LocalDate shipmentDate, String carrier, String trackingNo, int version, List<Item> items) {
    public record Item(long replacementLineId, int quantity) {}
}

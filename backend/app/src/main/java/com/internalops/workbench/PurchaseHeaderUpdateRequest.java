package com.internalops.workbench;

import java.time.LocalDate;

public record PurchaseHeaderUpdateRequest(
        LocalDate expectedArrivalDate,
        String deliveryAddress,
        String remark) {
}

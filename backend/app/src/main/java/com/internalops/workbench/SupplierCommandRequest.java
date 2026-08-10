package com.internalops.workbench;

import java.util.List;

public record SupplierCommandRequest(
        String supplierName,
        String manufacturerCategory,
        String manufacturerType,
        String supplierLocation,
        String productAttribute,
        String shortName,
        String contactName,
        String contactTitle,
        String phone,
        String address,
        String currency,
        String taxRegistrationNo,
        String bankAddress,
        String bankAccount,
        List<SupplierProductConfigRequest> products,
        Integer version) {
}

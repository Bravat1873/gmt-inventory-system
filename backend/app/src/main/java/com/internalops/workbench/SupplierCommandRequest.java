package com.internalops.workbench;

import java.util.List;

public record SupplierCommandRequest(
        String supplierName,
        String contactName,
        String phone,
        String bankAccount,
        List<SupplierProductConfigRequest> products,
        Integer version) {
}

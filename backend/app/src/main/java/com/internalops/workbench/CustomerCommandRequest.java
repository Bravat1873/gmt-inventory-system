package com.internalops.workbench;

import java.util.List;

public record CustomerCommandRequest(
        String customerCode, String customerName, String address,
        String businessContactName, String businessContactPhone,
        String orderContactName, String orderContactPhone,
        String financeContactName, String financeContactPhone,
        String invoiceTitle, String taxpayerId, String invoiceAddress, String invoicePhone,
        String bankName, String bankAccount, List<CustomerContractRequest> contracts, Integer version) {}

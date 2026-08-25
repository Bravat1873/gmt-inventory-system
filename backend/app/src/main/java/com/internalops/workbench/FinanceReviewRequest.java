package com.internalops.workbench;

import java.math.BigDecimal;

public record FinanceReviewRequest(
        boolean approved,
        BigDecimal confirmedAmount,
        String confirmedInvoiceNo,
        String reviewRemark
) {}

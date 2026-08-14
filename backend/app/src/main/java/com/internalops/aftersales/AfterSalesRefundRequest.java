package com.internalops.aftersales;

import java.math.BigDecimal;

public record AfterSalesRefundRequest(
        BigDecimal amount,
        BigDecimal suggestedAmount,
        String adjustmentReason
) {}
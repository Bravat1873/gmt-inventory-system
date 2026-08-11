package com.internalops.workbench;
import java.math.BigDecimal;import java.time.LocalDate;
public record FinanceActionRequest(
        BigDecimal amount,
        String paymentMethod,
        String invoiceNo,
        LocalDate invoiceDate,
        String paymentRemark,
        LocalDate receivedAt) {
}

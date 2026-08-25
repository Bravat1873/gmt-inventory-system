package com.internalops.workbench;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceRequest(String invoiceNo, LocalDate invoiceDate, BigDecimal taxInclusiveAmount, String remark) {}

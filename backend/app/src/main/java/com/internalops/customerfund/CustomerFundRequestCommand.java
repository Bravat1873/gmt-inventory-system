package com.internalops.customerfund;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate,
                                         String paymentMethod, String referenceNo, String remark) {}
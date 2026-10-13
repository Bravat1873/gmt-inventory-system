package com.internalops.customerfund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate,
                                          String paymentMethod, String referenceNo, String remark, Long orderId,
                                          LocalDateTime paymentAt, String fundSource) {
    public CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate, String paymentMethod,
                                      String referenceNo, String remark) {
        this(amount, paymentDate, paymentMethod, referenceNo, remark, null, null, "DOMESTIC");
    }

    public CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate, String paymentMethod,
                                      String referenceNo, String remark, Long orderId) {
        this(amount, paymentDate, paymentMethod, referenceNo, remark, orderId, null, "DOMESTIC");
    }
    public CustomerFundRequestCommand(BigDecimal amount, LocalDate paymentDate, String paymentMethod,
                                      String referenceNo, String remark, Long orderId, LocalDateTime paymentAt) {
        this(amount, paymentDate, paymentMethod, referenceNo, remark, orderId, paymentAt, "DOMESTIC");
    }
}

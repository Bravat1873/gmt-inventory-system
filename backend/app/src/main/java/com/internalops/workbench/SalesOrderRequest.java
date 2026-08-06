package com.internalops.workbench;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SalesOrderRequest(Long customerId, String externalOrderNo, LocalDate orderDate, String orderType,
                                String status, String salesperson, String customerContact, String customerPhone,
                                String remark, String deliveryAddress, String deliveryContact, String deliveryPhone,
                                String shippingMethod, Integer version, List<Item> items) {
    public record Item(Integer lineNo, Long skuId, Integer quantity, BigDecimal salePrice) {}
}

package com.internalops.workbench;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SalesOrderRequest(Long customerId, String externalOrderNo, LocalDate orderDate, String orderType,
                                String status, String salesperson, String customerContact, String customerPhone,
                                String businessContactName, String businessContactPhone,
                                String orderContactName, String orderContactPhone,
                                String financeContactName, String financeContactPhone,
                                String remark, String deliveryAddress, String deliveryContact, String deliveryPhone,
                                String shippingMethod, Integer version, List<Item> items, ReceiptConfirmation receiptConfirmation) {
    public record ReceiptConfirmation(BigDecimal originalAmount, BigDecimal amount, String reason) {}
    public record Item(Integer lineNo, Long skuId, Integer quantity, BigDecimal salePrice, String remark) {
        public Item(Integer lineNo, Long skuId, Integer quantity, BigDecimal salePrice) {
            this(lineNo, skuId, quantity, salePrice, null);
        }
    }
}

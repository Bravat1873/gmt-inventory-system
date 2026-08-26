package com.internalops.workbench;

import com.internalops.customerfund.CustomerFundService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class FinanceWorkflowService {
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;
    private final CustomerFundService customerFunds;

    public FinanceWorkflowService(JdbcTemplate jdbc, InventoryAllocationService allocation, CustomerFundService customerFunds) { this.jdbc = jdbc; this.allocation = allocation; this.customerFunds = customerFunds; }

    @Transactional
    public Map<String, Object> receipt(long id, FinanceActionRequest request) {
        Map<String, Object> order = order(id);
        String status = String.valueOf(order.get("status"));
        if ("DRAFT".equals(status)) throw new IllegalStateException("请先确认订单并分配库存后再登记收款");
        BigDecimal receivable = receivableAmount(id);
        BigDecimal received = receivedAmount(id);
        BigDecimal outstanding = receivable.subtract(received).max(BigDecimal.ZERO);
        BigDecimal receiptAmount = request.amount();
        if (receiptAmount == null || receiptAmount.signum() == 0) {
            throw new IllegalArgumentException("收款金额不能为 0；发票请单独维护");
        }
        if (hasText(request.invoiceNo()) || request.invoiceDate() != null) {
            throw new IllegalArgumentException("发票请单独维护，不能随收款登记");
        }
        if (receiptAmount.signum() > 0 && receiptAmount.compareTo(outstanding) > 0) {
            throw new IllegalArgumentException("本次收款金额不能超过未收金额");
        }
        if (receiptAmount.signum() < 0 && receiptAmount.abs().compareTo(received) > 0) {
            throw new IllegalArgumentException("退款金额不能超过已收金额");
        }

        LocalDateTime receiptTime = request.receivedAt() == null ? LocalDateTime.now() : request.receivedAt().atStartOfDay();
        jdbc.update("""
                        INSERT INTO customer_receipt(
                            sales_order_id,amount,payment_method,payment_remark,received_at,confirmed_by,review_status)
                        VALUES(?,?,?,?,?,NULL,'PENDING')
                        """,
                id, receiptAmount, text(request.paymentMethod(), "银行转账"), nullableText(request.paymentRemark()), receiptTime);
        BigDecimal customerBalance = order.get("customer_id") == null ? BigDecimal.ZERO : customerFunds.balance(((Number) order.get("customer_id")).longValue());
        if (customerBalance == null) customerBalance = BigDecimal.ZERO;
        return Map.of("id", id, "status", status, "receivableAmount", receivable, "receivedAmount", received,
                "outstandingAmount", outstanding, "receivedAt", receiptTime, "customerBalance", customerBalance,
                "reviewStatus", "PENDING");
    }

    private Map<String, Object> order(long id) { return jdbc.queryForMap("SELECT status,total_amount,customer_id,order_no FROM sales_order WHERE id=? FOR UPDATE", id); }

    private BigDecimal receivableAmount(long id) {
        BigDecimal result = jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity * i.sale_price), 0) FROM sales_order_item i WHERE i.sales_order_id=?", BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private BigDecimal receivedAmount(long id) {
        BigDecimal result = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM customer_receipt
                WHERE sales_order_id=? AND COALESCE(review_status, 'APPROVED')='APPROVED'
                """, BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String nullableText(String value) { return hasText(value) ? value.trim() : null; }
}

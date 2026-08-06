package com.internalops.workbench;

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

    public FinanceWorkflowService(JdbcTemplate jdbc, InventoryAllocationService allocation) { this.jdbc = jdbc; this.allocation = allocation; }

    @Transactional
    public Map<String, Object> receipt(long id, FinanceActionRequest request) {
        Map<String, Object> order = order(id);
        String status = String.valueOf(order.get("status"));
        if ("DRAFT".equals(status)) throw new IllegalStateException("请先确认订单并分配库存后再登记收款");
        BigDecimal receivable = receivableAmount(id);
        BigDecimal received = receivedAmount(id);
        BigDecimal outstanding = receivable.subtract(received).max(BigDecimal.ZERO);
        BigDecimal receiptAmount = request.amount();
        if (receiptAmount == null || receiptAmount.signum() <= 0) throw new IllegalArgumentException("本次收款金额必须大于 0");
        if (receiptAmount.compareTo(outstanding) > 0) throw new IllegalArgumentException("本次收款金额不能超过未收金额");

        jdbc.update("INSERT INTO customer_receipt(sales_order_id,amount,payment_method,received_at,confirmed_by) VALUES(?,?,?,?,1)", id, receiptAmount, text(request.paymentMethod(), "银行转账"), LocalDateTime.now());
        jdbc.update("UPDATE sales_order SET receipt_confirmed_at=?,version=version+1 WHERE id=?", LocalDateTime.now(), id);
        BigDecimal totalReceived = received.add(receiptAmount);
        return Map.of("id", id, "status", status, "receivableAmount", receivable, "receivedAmount", totalReceived, "outstandingAmount", receivable.subtract(totalReceived).max(BigDecimal.ZERO));
    }

    private Map<String, Object> order(long id) { return jdbc.queryForMap("SELECT status,total_amount FROM sales_order WHERE id=? FOR UPDATE", id); }

    private BigDecimal receivableAmount(long id) {
        BigDecimal result = jdbc.queryForObject("SELECT COALESCE(SUM((i.quantity - CASE WHEN o.status='SHIPPED' THEN i.quantity ELSE 0 END) * i.sale_price), 0) FROM sales_order_item i JOIN sales_order o ON o.id=i.sales_order_id WHERE i.sales_order_id=?", BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private BigDecimal receivedAmount(long id) {
        BigDecimal result = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM customer_receipt WHERE sales_order_id=?", BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
}

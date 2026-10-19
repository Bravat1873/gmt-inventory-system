package com.internalops.workbench;

import com.internalops.customerfund.CustomerFundService;
import com.internalops.auth.CurrentUser;

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

    @Transactional
    public Map<String, Object> payOrder(long id, BigDecimal expectedAmount) {
        return payOrder(id, expectedAmount, null);
    }

    @Transactional
    public Map<String, Object> payOrder(long id, BigDecimal expectedAmount, BigDecimal amount) {
        if (!CurrentUser.required().role().canWriteFinance()) throw new SecurityException("只有财务或管理员可以支付订单");
        Map<String, Object> order = order(id);
        String status = String.valueOf(order.get("status"));
        if ("DRAFT".equals(status) || "CANCELLED".equals(status)) throw new IllegalStateException("草稿或已取消订单不能支付");
        BigDecimal outstanding = receivableAmount(id).subtract(receivedAmount(id));
        if (outstanding.signum() <= 0) throw new IllegalStateException("订单已支付，无需重复支付");
        if (expectedAmount == null || outstanding.compareTo(expectedAmount) != 0) throw new IllegalStateException("订单待支付金额已变化，请重新打开支付窗口");
        BigDecimal paymentAmount = amount == null ? outstanding : amount;
        if (paymentAmount.signum() <= 0) throw new IllegalArgumentException("支付金额必须大于 0");
        if (paymentAmount.stripTrailingZeros().scale() > 4) throw new IllegalArgumentException("支付金额最多保留四位小数");
        if (paymentAmount.compareTo(outstanding) > 0) throw new IllegalArgumentException("本次支付金额不能超过未付金额");
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM customer_receipt WHERE sales_order_id=? AND review_status='PENDING'", Integer.class, id);
        if (pending != null && pending > 0) throw new IllegalStateException("订单存在待复核收款，请先在财务管理处理后再支付");
        LocalDateTime now = LocalDateTime.now();
        long userId = CurrentUser.required().id();
        customerFunds.debitForOrder(id, paymentAmount, "订单支付：客户余额抵扣");
        jdbc.update("""
                INSERT INTO customer_receipt(sales_order_id,amount,confirmed_amount,payment_method,payment_remark,
                    received_at,confirmed_by,review_status,reviewed_by,reviewed_at,review_remark)
                VALUES(?,?,?,'客户余额','订单支付',?,?,'APPROVED',?,?,'订单支付：客户余额抵扣')
                """, id, paymentAmount, paymentAmount, now, userId, userId, now);
        jdbc.update("UPDATE sales_order SET receipt_confirmed_at=?,updated_at=?,version=version+1 WHERE id=?", now, now, id);
        return Map.of("id", id, "paymentStatus", paymentAmount.compareTo(outstanding) == 0 ? "PAID" : "PARTIALLY_PAID",
                "paidAmount", paymentAmount, "receivedAmount", receivedAmount(id), "outstandingAmount", outstanding.subtract(paymentAmount),
                "customerBalance", customerFunds.balance(((Number) order.get("customer_id")).longValue()));
    }

    private BigDecimal receivableAmount(long id) {
        BigDecimal result = jdbc.queryForObject("SELECT COALESCE(SUM(i.quantity * i.sale_price), 0) FROM sales_order_item i WHERE i.sales_order_id=?", BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private BigDecimal receivedAmount(long id) {
        BigDecimal result = jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)), 0)
                FROM customer_receipt
                WHERE sales_order_id=? AND COALESCE(review_status, 'APPROVED')='APPROVED'
                """, BigDecimal.class, id);
        return result == null ? BigDecimal.ZERO : result;
    }

    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String nullableText(String value) { return hasText(value) ? value.trim() : null; }
}

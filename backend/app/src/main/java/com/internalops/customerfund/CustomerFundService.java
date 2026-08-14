package com.internalops.customerfund;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
public class CustomerFundService {
    private final JdbcTemplate jdbc;

    public CustomerFundService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public BigDecimal balance(long customerId) {
        BigDecimal value = jdbc.queryForObject("SELECT balance FROM customer_fund_account WHERE customer_id=?", BigDecimal.class, customerId);
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public long submitDeposit(long customerId, CustomerFundRequestCommand command) {
        requirePositive(command == null ? null : command.amount());
        ensureCustomer(customerId);
        String sourceType = null;
        Long sourceId = null;
        if (command.orderId() != null) {
            Map<String,Object> order = one("SELECT customer_id,order_no,status FROM sales_order WHERE id=?", command.orderId());
            if (number(order, "customer_id") != customerId) throw new IllegalArgumentException("只能关联当前客户的订单");
            if ("CANCELLED".equals(string(order.get("status")))) throw new IllegalArgumentException("已取消订单不能关联打款");
            sourceType = "SALES_ORDER";
            sourceId = command.orderId();
        }
        CurrentUser user = CurrentUser.required();
        return insertRequest(customerId, "CUSTOMER_DEPOSIT", command.amount(), command.paymentDate(),
                command.paymentMethod(), command.referenceNo(), sourceType, sourceId, command.remark(), user.id());
    }

    @Transactional
    public long submitAfterSalesRefund(long afterSalesId, BigDecimal amount, BigDecimal suggestedAmount, String adjustmentReason) {
        requirePositive(amount);
        requirePositive(suggestedAmount);
        if (amount.compareTo(suggestedAmount) != 0 && blank(adjustmentReason)) {
            throw new IllegalArgumentException("退款金额与建议金额不一致时必须填写调整原因");
        }
        Map<String,Object> afterSales = one("SELECT customer_id,after_sales_no,after_sales_type,status FROM after_sales_order WHERE id=?", afterSalesId);
        if (!"RETURN".equals(String.valueOf(afterSales.get("after_sales_type"))) || !"RETURN_RECEIVED".equals(String.valueOf(afterSales.get("status")))) {
            throw new IllegalStateException("只有已确认收货的退货单可以申请退款");
        }
        return insertRequest(number(afterSales, "customer_id"), "AFTER_SALES_REFUND", amount, null,
                null, null, "AFTER_SALES", afterSalesId, adjustmentReason, CurrentUser.required().id());
    }

    @Transactional
    public void review(long requestId, CustomerFundReviewCommand command) {
        requireReviewer();
        Map<String,Object> request = one("SELECT * FROM customer_fund_request WHERE id=? FOR UPDATE", requestId);
        if (!"PENDING".equals(String.valueOf(request.get("status")))) throw new IllegalStateException("资金申请已处理");
        CurrentUser reviewer = CurrentUser.required();
        String status = command.approved() ? "APPROVED" : "REJECTED";
        int changed = jdbc.update("UPDATE customer_fund_request SET status=?,review_comment=?,reviewed_by=?,reviewed_at=?,version=version+1 WHERE id=? AND status='PENDING'",
                status, clean(command.comment()), reviewer.id(), LocalDateTime.now(), requestId);
        if (changed != 1) throw new IllegalStateException("资金申请已处理");
        if (!command.approved()) return;
        String type = String.valueOf(request.get("request_type"));
        post(number(request, "customer_id"), type, "IN", decimal(request.get("amount")), requestId,
                string(request.get("source_type")), nullableLong(request.get("source_id")), sourceNo(request), null,
                clean(command.comment()));
    }

    @Transactional
    public long debitForOrder(long orderId, BigDecimal amount, String reason) {
        requirePositive(amount);
        Map<String,Object> order = one("SELECT customer_id,order_no FROM sales_order WHERE id=? FOR UPDATE", orderId);
        return post(number(order, "customer_id"), "ORDER_RECEIPT", "OUT", amount, null,
                "SALES_ORDER", orderId, String.valueOf(order.get("order_no")), null, clean(reason));
    }

    @Transactional
    public long reverse(long ledgerId, CustomerFundReversalCommand command) {
        requireReviewer();
        if (command == null || blank(command.reason())) throw new IllegalArgumentException("请填写冲正原因");
        Map<String,Object> original = one("SELECT * FROM customer_fund_ledger WHERE id=? FOR UPDATE", ledgerId);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM customer_fund_ledger WHERE reverses_ledger_id=?", Integer.class, ledgerId);
        if (exists != null && exists > 0) throw new IllegalStateException("资金流水已冲正");
        String reverseDirection = "IN".equals(String.valueOf(original.get("direction"))) ? "OUT" : "IN";
        long id = post(number(original, "customer_id"), "REVERSAL", reverseDirection, decimal(original.get("amount")), null,
                "FUND_LEDGER", ledgerId, null, ledgerId, command.reason().trim());
        Object requestId = original.get("request_id");
        if (requestId != null) jdbc.update("UPDATE customer_fund_request SET status='REVERSED',version=version+1 WHERE id=?", requestId);
        return id;
    }

    private long post(long customerId, String type, String direction, BigDecimal amount, Long requestId,
                      String sourceType, Long sourceId, String sourceNo, Long reversesId, String reason) {
        Map<String,Object> account = one("SELECT balance,version FROM customer_fund_account WHERE customer_id=? FOR UPDATE", customerId);
        BigDecimal before = decimal(account.get("balance"));
        BigDecimal after = "IN".equals(direction) ? before.add(amount) : before.subtract(amount);
        if (after.signum() < 0) throw new IllegalArgumentException("客户余额不足");
        int changed = jdbc.update("UPDATE customer_fund_account SET balance=?,version=version+1,updated_at=? WHERE customer_id=? AND version=?",
                after, LocalDateTime.now(), customerId, ((Number)account.get("version")).intValue());
        if (changed != 1) throw new IllegalStateException("客户余额已变化，请重试");
        return insert("INSERT INTO customer_fund_ledger(customer_id,entry_type,direction,amount,balance_before,balance_after,request_id,source_type,source_id,source_no,reverses_ledger_id,reason,operated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                customerId, type, direction, amount, before, after, requestId, sourceType, sourceId, sourceNo, reversesId, reason, CurrentUser.required().id());
    }

    private long insertRequest(long customerId, String type, BigDecimal amount, Object date, String method,
                               String referenceNo, String sourceType, Long sourceId, String remark, long userId) {
        return insert("INSERT INTO customer_fund_request(customer_id,request_type,status,amount,payment_date,payment_method,reference_no,source_type,source_id,remark,submitted_by) VALUES(?,?,'PENDING',?,?,?,?,?,?,?,?)",
                customerId, type, amount, date, clean(method), clean(referenceNo), sourceType, sourceId, clean(remark), userId);
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void ensureCustomer(long customerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=?", Integer.class, customerId);
        if (count == null || count == 0) throw new IllegalArgumentException("客户不存在");
    }
    private void requireReviewer() {
        UserRole role = CurrentUser.required().role();
        if (role != UserRole.ADMIN && role != UserRole.FINANCE) throw new SecurityException("只有财务或管理员可以审核资金记录");
    }
    private Map<String,Object> one(String sql, Object... args) {
        var rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("业务记录不存在");
        return rows.get(0);
    }
    private String sourceNo(Map<String,Object> request) {
        String type = string(request.get("source_type"));
        Long id = nullableLong(request.get("source_id"));
        if (id == null) return null;
        if ("SALES_ORDER".equals(type)) return jdbc.queryForObject("SELECT order_no FROM sales_order WHERE id=?", String.class, id);
        if ("AFTER_SALES".equals(type)) return jdbc.queryForObject("SELECT after_sales_no FROM after_sales_order WHERE id=?", String.class, id);
        return null;
    }
    private void requirePositive(BigDecimal value) { if (value == null || value.signum() <= 0) throw new IllegalArgumentException("金额必须大于 0"); }
    private long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private Long nullableLong(Object value) { return value == null ? null : ((Number)value).longValue(); }
    private BigDecimal decimal(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value)); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String clean(String value) { return blank(value) ? null : value.trim(); }
}
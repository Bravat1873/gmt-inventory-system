package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class FinanceInvoiceService {
    private final JdbcTemplate jdbc;

    public FinanceInvoiceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> list(String type, long businessId) {
        return jdbc.query("SELECT id,invoice_no,confirmed_invoice_no,invoice_date,tax_inclusive_amount,confirmed_amount,remark,COALESCE(review_status,'APPROVED') AS review_status,review_remark,reviewed_at,created_at,updated_at FROM " + table(type) + " WHERE " + foreignKey(type) + "=? ORDER BY id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("invoiceNo", rs.getString("invoice_no"));
                    row.put("confirmedInvoiceNo", rs.getString("confirmed_invoice_no"));
                    row.put("invoiceDate", rs.getObject("invoice_date"));
                    row.put("taxInclusiveAmount", rs.getBigDecimal("tax_inclusive_amount"));
                    row.put("confirmedAmount", rs.getBigDecimal("confirmed_amount"));
                    row.put("remark", rs.getString("remark"));
                    row.put("reviewStatus", rs.getString("review_status"));
                    row.put("reviewRemark", rs.getString("review_remark"));
                    row.put("reviewedAt", rs.getObject("reviewed_at"));
                    row.put("createdAt", rs.getObject("created_at"));
                    row.put("updatedAt", rs.getObject("updated_at"));
                    return row;
                }, businessId);
    }

    @Transactional
    public Map<String, Object> save(String type, long businessId, InvoiceRequest request) {
        requireFinance();
        if (request == null || request.invoiceNo() == null || request.invoiceNo().isBlank()) throw new IllegalArgumentException("发票号码不能为空");
        ensureBusiness(type, businessId);
        String table = table(type);
        String key = foreignKey(type);
        jdbc.update("INSERT INTO " + table + "(" + key + ",invoice_no,invoice_date,tax_inclusive_amount,remark,review_status,created_by,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)", businessId, request.invoiceNo().trim(), request.invoiceDate(), request.taxInclusiveAmount(), clean(request.remark()), CurrentUser.required().id(), LocalDateTime.now());
        Long id = jdbc.queryForObject("SELECT id FROM " + table + " WHERE invoice_no=?", Long.class, request.invoiceNo().trim());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("businessId", businessId);
        result.put("invoiceNo", request.invoiceNo().trim());
        result.put("taxInclusiveAmount", request.taxInclusiveAmount());
        result.put("reviewStatus", "PENDING");
        return result;
    }

    @Transactional
    public void delete(String type, long businessId) {
        requireFinance();
        jdbc.update("DELETE FROM " + table(type) + " WHERE " + foreignKey(type) + "=?", businessId);
    }

    @Transactional
    public void delete(String type, long businessId, long invoiceId) {
        requireFinance();
        int changed = jdbc.update("DELETE FROM " + table(type) + " WHERE id=? AND " + foreignKey(type) + "=?", invoiceId, businessId);
        if (changed == 0) throw new IllegalArgumentException("发票记录不存在");
    }

    private void ensureBusiness(String type, long id) {
        String table = "SALES".equalsIgnoreCase(type) ? "sales_order" : "PURCHASE".equalsIgnoreCase(type) ? "purchase_order" : null;
        if (table == null || Boolean.FALSE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=?)", Boolean.class, id))) throw new IllegalArgumentException("业务单据不存在");
    }
    private static String table(String type) { if ("SALES".equalsIgnoreCase(type)) return "sales_invoice"; if ("PURCHASE".equalsIgnoreCase(type)) return "purchase_invoice"; throw new IllegalArgumentException("不支持的业务类型"); }
    private static String foreignKey(String type) { return "SALES".equalsIgnoreCase(type) ? "sales_order_id" : "PURCHASE".equalsIgnoreCase(type) ? "purchase_order_id" : invalid(); }
    private static String invalid() { throw new IllegalArgumentException("不支持的业务类型"); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void requireFinance() { UserRole role = CurrentUser.required().role(); if (role != UserRole.ADMIN && role != UserRole.FINANCE) throw new IllegalStateException("仅管理员或财务可维护发票"); }
}

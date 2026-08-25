package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.customerfund.CustomerFundService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FinanceReviewService {
    private final JdbcTemplate jdbc; private final CustomerFundService customerFunds; private final ProcurementWorkflowService procurement;
    public FinanceReviewService(JdbcTemplate jdbc, CustomerFundService customerFunds, ProcurementWorkflowService procurement) { this.jdbc=jdbc;this.customerFunds=customerFunds;this.procurement=procurement; }
    public List<Map<String,Object>> records(String type,long id) {
        if ("SALES".equalsIgnoreCase(type)) return queryRecords("SELECT id,amount,confirmed_amount,payment_method,payment_remark,received_at AS occurred_at,COALESCE(review_status,'APPROVED') AS review_status,reviewed_at,review_remark FROM customer_receipt WHERE sales_order_id=? ORDER BY received_at DESC,id DESC",id);
        if ("PURCHASE".equalsIgnoreCase(type)) return queryRecords("SELECT id,amount,confirmed_amount,payment_method,payment_remark,paid_at AS occurred_at,COALESCE(review_status,'APPROVED') AS review_status,reviewed_at,review_remark FROM supplier_payment WHERE purchase_order_id=? ORDER BY paid_at DESC,id DESC",id);
        throw new IllegalArgumentException("不支持的业务类型");
    }
    @Transactional public Map<String,Object> reviewReceipt(long id,FinanceReviewRequest r) {
        requireReviewer(); Map<String,Object> row=jdbc.queryForMap("SELECT cr.*,o.order_no FROM customer_receipt cr JOIN sales_order o ON o.id=cr.sales_order_id WHERE cr.id=? FOR UPDATE",id); ensurePending(row);
        if(!r.approved()) return reject("customer_receipt",id,r.reviewRemark()); BigDecimal amount=decimal(row.get("amount"));BigDecimal confirmedAmount=confirmedAmount(amount,r.confirmedAmount());long orderId=num(row.get("sales_order_id"));
        if(confirmedAmount.signum()>0){customerFunds.debitForOrder(orderId,confirmedAmount,r.reviewRemark());jdbc.update("UPDATE sales_order SET receipt_confirmed_at=? WHERE id=?",LocalDateTime.now(),orderId);}else{BigDecimal approved=jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)),0) FROM customer_receipt WHERE sales_order_id=? AND id<>? AND COALESCE(review_status,'APPROVED')='APPROVED'",BigDecimal.class,orderId,id);if(confirmedAmount.abs().compareTo(approved)>0)throw new IllegalArgumentException("退款金额不能超过已收金额");customerFunds.creditForOrder(orderId,confirmedAmount.abs(),r.reviewRemark());}
        approveMoney("customer_receipt",id,confirmedAmount,r.reviewRemark());return Map.of("id",id,"reviewStatus","APPROVED","confirmedAmount",confirmedAmount);
    }
    @Transactional public Map<String,Object> reviewPayment(long id,FinanceReviewRequest r) {
        requireReviewer();Map<String,Object> row=jdbc.queryForMap("SELECT * FROM supplier_payment WHERE id=? FOR UPDATE",id);ensurePending(row);if(!r.approved())return reject("supplier_payment",id,r.reviewRemark());BigDecimal confirmedAmount=confirmedAmount(decimal(row.get("amount")),r.confirmedAmount());approveMoney("supplier_payment",id,confirmedAmount,r.reviewRemark());procurement.updatePurchaseProgressStatus(num(row.get("purchase_order_id")));return Map.of("id",id,"reviewStatus","APPROVED","confirmedAmount",confirmedAmount);
    }
    @Transactional public Map<String,Object> reviewInvoice(long id,FinanceReviewRequest r) {
        requireReviewer();Map<String,Object> row=findInvoiceForUpdate(id);ensurePending(row);String table=String.valueOf(row.get("table_name"));if(!r.approved())return reject(table,id,r.reviewRemark());
        BigDecimal confirmedAmount=confirmedAmount(decimal(row.get("tax_inclusive_amount")),r.confirmedAmount());String confirmedNo=clean(r.confirmedInvoiceNo());if(confirmedNo==null)confirmedNo=String.valueOf(row.get("invoice_no")).trim();if(confirmedNo.isBlank())throw new IllegalArgumentException("财务确认发票号码不能为空");
        jdbc.update("UPDATE "+table+" SET review_status='APPROVED',confirmed_invoice_no=?,confirmed_amount=?,reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",confirmedNo,confirmedAmount,CurrentUser.required().id(),LocalDateTime.now(),clean(r.reviewRemark()),id);
        return Map.of("id",id,"reviewStatus","APPROVED","confirmedInvoiceNo",confirmedNo,"confirmedAmount",confirmedAmount);
    }
    public Map<String,Object> reviewSummary(String type,long id) {
        String normalized=normalizeType(type);String moneyTable="SALES".equals(normalized)?"customer_receipt":"supplier_payment";String invoiceTable="SALES".equals(normalized)?"sales_invoice":"purchase_invoice";String moneyKey="SALES".equals(normalized)?"sales_order_id":"purchase_order_id";String invoiceKey=moneyKey;
        BigDecimal moneyTotal=jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)),0) FROM "+moneyTable+" WHERE "+moneyKey+"=? AND COALESCE(review_status,'APPROVED')='APPROVED'",BigDecimal.class,id);
        BigDecimal invoiceTotal=jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,tax_inclusive_amount)),0) FROM "+invoiceTable+" WHERE "+invoiceKey+"=? AND COALESCE(review_status,'APPROVED')='APPROVED'",BigDecimal.class,id);
        Integer pendingMoney=jdbc.queryForObject("SELECT COUNT(*) FROM "+moneyTable+" WHERE "+moneyKey+"=? AND review_status='PENDING'",Integer.class,id);
        Integer pendingInvoice=jdbc.queryForObject("SELECT COUNT(*) FROM "+invoiceTable+" WHERE "+invoiceKey+"=? AND review_status='PENDING'",Integer.class,id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("type",type);result.put("id",id);result.put("confirmedMoneyAmount",moneyTotal);result.put("pendingMoneyCount",pendingMoney);result.put("moneyRecords",records(normalized,id));result.put("confirmedInvoiceAmount",invoiceTotal);result.put("pendingInvoiceCount",pendingInvoice);result.put("invoiceRecords",invoiceRecords(invoiceTable,invoiceKey,id));result.put("differenceAmount",moneyTotal.subtract(invoiceTotal).abs());return result;
    }
    private Map<String,Object> reject(String table,long id,String remark){jdbc.update("UPDATE "+table+" SET review_status='REJECTED',reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",CurrentUser.required().id(),LocalDateTime.now(),clean(remark),id);return Map.of("id",id,"reviewStatus","REJECTED");}
    private void approve(String table,long id,String remark){jdbc.update("UPDATE "+table+" SET review_status='APPROVED',reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",CurrentUser.required().id(),LocalDateTime.now(),clean(remark),id);}
    private void approveMoney(String table,long id,BigDecimal confirmedAmount,String remark){jdbc.update("UPDATE "+table+" SET review_status='APPROVED',confirmed_amount=?,reviewed_by=?,reviewed_at=?,review_remark=? WHERE id=?",confirmedAmount,CurrentUser.required().id(),LocalDateTime.now(),clean(remark),id);}
    private static void ensurePending(Map<String,Object> row){if(!"PENDING".equals(String.valueOf(row.get("review_status"))))throw new IllegalStateException("该资金记录已完成复核");}
    private static void requireReviewer(){UserRole role=CurrentUser.required().role();if(role!=UserRole.ADMIN&&role!=UserRole.FINANCE)throw new IllegalStateException("仅管理员或财务可复核资金记录");}
    private List<Map<String,Object>> queryRecords(String sql,long id){return jdbc.query(sql,(rs,rowNum)->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getLong("id"));row.put("amount",rs.getBigDecimal("amount"));row.put("confirmedAmount",rs.getBigDecimal("confirmed_amount"));row.put("paymentMethod",rs.getString("payment_method"));row.put("paymentRemark",rs.getString("payment_remark"));row.put("occurredAt",rs.getTimestamp("occurred_at"));row.put("reviewStatus",rs.getString("review_status"));row.put("reviewedAt",rs.getTimestamp("reviewed_at"));row.put("reviewRemark",rs.getString("review_remark"));return row;},id);}
    private List<Map<String,Object>> invoiceRecords(String table,String key,long id){return jdbc.query("SELECT id,invoice_no,confirmed_invoice_no,invoice_date,tax_inclusive_amount,confirmed_amount,remark,COALESCE(review_status,'APPROVED') AS review_status,review_remark,reviewed_at FROM "+table+" WHERE "+key+"=? ORDER BY id DESC",(rs,rowNum)->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getLong("id"));row.put("invoiceNo",rs.getString("invoice_no"));row.put("confirmedInvoiceNo",rs.getString("confirmed_invoice_no"));row.put("invoiceDate",rs.getObject("invoice_date"));row.put("taxInclusiveAmount",rs.getBigDecimal("tax_inclusive_amount"));row.put("confirmedAmount",rs.getBigDecimal("confirmed_amount"));row.put("remark",rs.getString("remark"));row.put("reviewStatus",rs.getString("review_status"));row.put("reviewRemark",rs.getString("review_remark"));row.put("reviewedAt",rs.getObject("reviewed_at"));return row;},id);}
    private Map<String,Object> findInvoiceForUpdate(long id){try{return jdbc.queryForMap("SELECT 'sales_invoice' AS table_name,id,invoice_no,tax_inclusive_amount,review_status FROM sales_invoice WHERE id=? FOR UPDATE",id);}catch(EmptyResultDataAccessException ignored){try{return jdbc.queryForMap("SELECT 'purchase_invoice' AS table_name,id,invoice_no,tax_inclusive_amount,review_status FROM purchase_invoice WHERE id=? FOR UPDATE",id);}catch(EmptyResultDataAccessException e){throw new IllegalArgumentException("发票记录不存在");}}}
    private static BigDecimal confirmedAmount(BigDecimal original,BigDecimal requested){if(requested==null)return original;if(requested.signum()<=0)throw new IllegalArgumentException("财务确认金额必须大于 0");return original.signum()<0?requested.negate():requested;}
    private static String normalizeType(String type){if("SALES".equalsIgnoreCase(type)||"RECEIVABLE".equalsIgnoreCase(type))return "SALES";if("PURCHASE".equalsIgnoreCase(type)||"PAYABLE".equalsIgnoreCase(type))return "PURCHASE";throw new IllegalArgumentException("不支持的业务类型");}
    private static long num(Object v){return ((Number)v).longValue();} private static BigDecimal decimal(Object v){return v instanceof BigDecimal b?b:new BigDecimal(String.valueOf(v));} private static String clean(String v){return v==null||v.isBlank()?null:v.trim();}
}

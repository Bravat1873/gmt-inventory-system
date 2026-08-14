package com.internalops.customerfund;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class CustomerFundQueryService {
    private final JdbcTemplate jdbc;
    public CustomerFundQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String,Object> overview(long customerId) {
        BigDecimal balance = amount("SELECT COALESCE(balance,0) FROM customer_fund_account WHERE customer_id=?", customerId);
        BigDecimal receivable = amount("SELECT COALESCE(SUM(i.quantity*i.sale_price),0) FROM sales_order_item i JOIN sales_order o ON o.id=i.sales_order_id WHERE o.customer_id=? AND o.status<>'DRAFT'", customerId);
        BigDecimal received = amount("SELECT COALESCE(SUM(r.amount),0) FROM customer_receipt r JOIN sales_order o ON o.id=r.sales_order_id WHERE o.customer_id=? AND o.status<>'DRAFT'", customerId);
        BigDecimal outstanding = receivable.subtract(received).max(BigDecimal.ZERO);
        BigDecimal pending = amount("SELECT COALESCE(SUM(amount),0) FROM customer_fund_request WHERE customer_id=? AND status='PENDING'", customerId);
        BigDecimal coverage = outstanding.signum() == 0 ? new BigDecimal("100") : balance.multiply(new BigDecimal("100")).divide(outstanding, 2, RoundingMode.HALF_UP);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("customerId", customerId); result.put("balance", balance); result.put("orderOutstandingAmount", outstanding);
        result.put("coverageRatio", coverage); result.put("pendingAmount", pending); result.put("insufficient", balance.compareTo(outstanding) < 0);
        return result;
    }

    public List<Map<String,Object>> requests(long customerId) {
        return camel(jdbc.queryForList("SELECT id,request_type AS `requestType`,status,amount,payment_date AS `paymentDate`,payment_method AS `paymentMethod`,reference_no AS `referenceNo`,source_type AS `sourceType`,source_id AS `sourceId`,remark,review_comment AS `reviewComment`,submitted_by AS `submittedBy`,submitted_at AS `submittedAt`,reviewed_by AS `reviewedBy`,reviewed_at AS `reviewedAt` FROM customer_fund_request WHERE customer_id=? ORDER BY submitted_at DESC,id DESC", customerId));
    }

    public List<Map<String,Object>> orderOptions(long customerId) {
        return camel(jdbc.queryForList("SELECT id,order_no AS `orderNo`,status,order_date AS `orderDate` FROM sales_order WHERE customer_id=? AND status<>'CANCELLED' ORDER BY updated_at DESC,id DESC", customerId));
    }

    public List<Map<String,Object>> ledger(long customerId) {
        return camel(jdbc.queryForList("SELECT id,entry_type AS `entryType`,direction,amount,balance_before AS `balanceBefore`,balance_after AS `balanceAfter`,request_id AS `requestId`,source_type AS `sourceType`,source_id AS `sourceId`,source_no AS `sourceNo`,reverses_ledger_id AS `reversesLedgerId`,reason,operated_by AS `operatedBy`,operated_at AS `operatedAt` FROM customer_fund_ledger WHERE customer_id=? ORDER BY operated_at DESC,id DESC", customerId));
    }

    public List<Map<String,Object>> summary(long customerId, String period, LocalDate from, LocalDate to) {
        String normalized = Set.of("DAY","MONTH","YEAR").contains(period == null ? "" : period.toUpperCase()) ? period.toUpperCase() : "MONTH";
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT entry_type,direction,amount,balance_before,balance_after,operated_at FROM customer_fund_ledger WHERE customer_id=? AND operated_at>=? AND operated_at<? ORDER BY operated_at,id", customerId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Map<String,Accumulator> groups = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            LocalDateTime time = localDateTime(row.get("operated_at"));
            String key = switch (normalized) { case "DAY" -> time.toLocalDate().toString(); case "YEAR" -> String.valueOf(time.getYear()); default -> time.format(DateTimeFormatter.ofPattern("yyyy-MM")); };
            groups.computeIfAbsent(key, ignored -> new Accumulator(decimal(row.get("balance_before")))).add(row);
        }
        return groups.entrySet().stream().map(entry -> entry.getValue().view(entry.getKey())).toList();
    }

    public String csv(long customerId, String period, LocalDate from, LocalDate to) {
        StringBuilder csv = new StringBuilder("\uFEFF周期,客户打款,订单收款扣减,售后退款入账,冲正金额,资金净变动,期初余额,期末余额\r\n");
        for (Map<String,Object> row : summary(customerId, period, from, to)) {
            csv.append(row.get("period")).append(',').append(row.get("depositAmount")).append(',').append(row.get("receiptAmount")).append(',')
                    .append(row.get("refundAmount")).append(',').append(row.get("reversalAmount")).append(',').append(row.get("netChange"))
                    .append(',').append(row.get("openingBalance")).append(',').append(row.get("closingBalance")).append("\r\n");
        }
        return csv.toString();
    }

    private List<Map<String,Object>> camel(List<Map<String,Object>> rows) {
        Map<String,String> names = Map.ofEntries(
                Map.entry("requesttype","requestType"), Map.entry("paymentdate","paymentDate"), Map.entry("paymentmethod","paymentMethod"),
                Map.entry("referenceno","referenceNo"), Map.entry("sourcetype","sourceType"), Map.entry("sourceid","sourceId"),
                Map.entry("reviewcomment","reviewComment"), Map.entry("submittedby","submittedBy"), Map.entry("submittedat","submittedAt"),
                Map.entry("reviewedby","reviewedBy"), Map.entry("reviewedat","reviewedAt"), Map.entry("entrytype","entryType"),
                Map.entry("balancebefore","balanceBefore"), Map.entry("balanceafter","balanceAfter"), Map.entry("requestid","requestId"),
                Map.entry("sourceno","sourceNo"), Map.entry("reversesledgerid","reversesLedgerId"), Map.entry("operatedby","operatedBy"),
                Map.entry("operatedat","operatedAt"), Map.entry("orderno","orderNo"), Map.entry("orderdate","orderDate"));
        return rows.stream().map(row -> { Map<String,Object> result=new LinkedHashMap<>(); row.forEach((key,value)->result.put(names.getOrDefault(key.replace("_","").toLowerCase(),key.toLowerCase()),value)); return result; }).toList();
    }
    private BigDecimal amount(String sql, long id) { BigDecimal value=jdbc.queryForObject(sql,BigDecimal.class,id); return value==null?BigDecimal.ZERO:value; }
    private static BigDecimal decimal(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value)); }
    static LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalArgumentException("Unsupported fund ledger time type: " + value);
    }
    private static final class Accumulator {
        BigDecimal deposit=BigDecimal.ZERO,receipt=BigDecimal.ZERO,refund=BigDecimal.ZERO,reversal=BigDecimal.ZERO,opening,closing;
        Accumulator(BigDecimal opening){this.opening=opening;this.closing=opening;}
        void add(Map<String,Object> row){BigDecimal value=decimal(row.get("amount"));String type=String.valueOf(row.get("entry_type"));String direction=String.valueOf(row.get("direction"));switch(type){case "CUSTOMER_DEPOSIT"->deposit=deposit.add(value);case "ORDER_RECEIPT"->receipt=receipt.add(value);case "AFTER_SALES_REFUND"->refund=refund.add(value);case "REVERSAL"->reversal=reversal.add("IN".equals(direction)?value:value.negate());default->{}}closing=decimal(row.get("balance_after"));}
        Map<String,Object> view(String period){Map<String,Object> v=new LinkedHashMap<>();v.put("period",period);v.put("depositAmount",deposit);v.put("receiptAmount",receipt);v.put("refundAmount",refund);v.put("reversalAmount",reversal);v.put("netChange",closing.subtract(opening));v.put("openingBalance",opening);v.put("closingBalance",closing);return v;}
    }
}
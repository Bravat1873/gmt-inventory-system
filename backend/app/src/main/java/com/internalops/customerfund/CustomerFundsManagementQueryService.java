package com.internalops.customerfund;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class CustomerFundsManagementQueryService {
    private static final Map<String,String> FIELD_NAMES = new HashMap<>();
    static {
        for(String name : List.of("fundSource","customerId","customerCode","customerName","operationNo","entryType","requestType",
                "balanceBefore","balanceAfter","sourceNo","submittedAt","submittedByName","confirmedAmount",
                "paymentDate","paymentAt","paymentMethod","referenceNo","sourceType","reviewComment","reviewedByName",
                "reviewedAt","reversalReason","reversalByName","reversalAt","ledgerId","pendingReceipt","pendingRefund","confirmedReceipt","confirmedRefund"))
            FIELD_NAMES.put(name.toLowerCase(Locale.ROOT),name);
    }
    private final JdbcTemplate jdbc;
    public CustomerFundsManagementQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Map<String, Object>> customers() {
        return rows("""
                SELECT c.id AS `id`,c.customer_code AS `customerCode`,c.customer_name AS `customerName`,
                       c.enabled AS `enabled`,COALESCE(a.balance,0) AS `balance`
                FROM customer c LEFT JOIN customer_fund_account a ON a.customer_id=c.id
                ORDER BY c.customer_name,c.id
                """, List.of());
    }

    public Map<String, Object> operations(boolean ledger, int page, Long customerId, String keyword,
                                          String status, String direction, String fundSource, LocalDate from, LocalDate to) {
        return operations(ledger,page,customerId,keyword,status,direction,fundSource,from,to,false);
    }

    public Map<String, Object> operations(boolean ledger, int page, Long customerId, String keyword,
                                          String status, String direction, String fundSource, LocalDate from, LocalDate to, boolean exportAll) {
        if (from != null && to != null && to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        if (status != null && !Set.of("PENDING","APPROVED","REJECTED","REVERSED").contains(status)) throw new IllegalArgumentException("不支持的资金状态");
        if (direction != null && !Set.of("IN","OUT").contains(direction)) throw new IllegalArgumentException("不支持的资金方向");
        if (fundSource != null && !Set.of("DOMESTIC","FOREX").contains(fundSource)) throw new IllegalArgumentException("资金来源请选择外汇或国内");
        String select = ledger ? """
                SELECT l.id AS `id`,l.customer_id AS `customerId`,c.customer_code AS `customerCode`,c.customer_name AS `customerName`,
                       CASE WHEN l.reverses_ledger_id IS NOT NULL THEN CONCAT('LC',l.id) ELSE COALESCE(r.operation_no,CONCAT('LC',l.id)) END AS `operationNo`,l.entry_type AS `entryType`,l.direction AS `direction`,
                       r.fund_source AS `fundSource`,l.amount AS `amount`,l.balance_before AS `balanceBefore`,l.balance_after AS `balanceAfter`,
                       l.source_no AS `sourceNo`,l.reason AS `remark`,l.operated_at AS `submittedAt`,u.display_name AS `submittedByName`,
                       CASE WHEN l.entry_type='REVERSAL' THEN l.reason ELSE rev.reason END AS `reversalReason`,
                       reversal_user.display_name AS `reversalByName`,
                       CASE WHEN l.entry_type='REVERSAL' THEN l.operated_at ELSE rev.operated_at END AS `reversalAt`,
                       CASE WHEN EXISTS(SELECT 1 FROM customer_fund_ledger rev WHERE rev.reverses_ledger_id=l.id) THEN 'REVERSED' ELSE 'APPROVED' END AS `status`
                """ : """
                SELECT r.id AS `id`,r.customer_id AS `customerId`,c.customer_code AS `customerCode`,c.customer_name AS `customerName`,
                       r.operation_no AS `operationNo`,r.request_type AS `requestType`,r.status AS `status`,ABS(r.amount) AS `amount`,
                       CASE WHEN r.amount<0 THEN 'OUT' ELSE 'IN' END AS `direction`,ABS(r.confirmed_amount) AS `confirmedAmount`,
                       r.payment_date AS `paymentDate`,r.payment_at AS `paymentAt`,r.payment_method AS `paymentMethod`,
                       r.fund_source AS `fundSource`,r.reference_no AS `referenceNo`,r.source_type AS `sourceType`,o.order_no AS `sourceNo`,
                       r.remark AS `remark`,r.review_comment AS `reviewComment`,r.submitted_at AS `submittedAt`,
                       u.display_name AS `submittedByName`,reviewer.display_name AS `reviewedByName`,r.reviewed_at AS `reviewedAt`,
                       l.id AS `ledgerId`,rev.reason AS `reversalReason`,reversal_user.display_name AS `reversalByName`,
                       rev.operated_at AS `reversalAt`
                """;
        String join = ledger ? """
                FROM customer_fund_ledger l JOIN customer c ON c.id=l.customer_id
                LEFT JOIN customer_fund_request r ON r.id=COALESCE(l.request_id,
                    (SELECT original.request_id FROM customer_fund_ledger original WHERE original.id=l.reverses_ledger_id))
                LEFT JOIN sys_user u ON u.id=l.operated_by
                LEFT JOIN customer_fund_ledger rev ON rev.reverses_ledger_id=l.id
                LEFT JOIN sys_user reversal_user ON reversal_user.id=CASE WHEN l.entry_type='REVERSAL' THEN l.operated_by ELSE rev.operated_by END
                """ : """
                FROM customer_fund_request r JOIN customer c ON c.id=r.customer_id
                LEFT JOIN sys_user u ON u.id=r.submitted_by LEFT JOIN sys_user reviewer ON reviewer.id=r.reviewed_by
                LEFT JOIN sales_order o ON r.source_type='SALES_ORDER' AND o.id=r.source_id
                LEFT JOIN customer_fund_ledger l ON l.request_id=r.id
                LEFT JOIN customer_fund_ledger rev ON rev.reverses_ledger_id=l.id
                LEFT JOIN sys_user reversal_user ON reversal_user.id=rev.operated_by
                """;
        String prefix = ledger ? "l" : "r";
        StringBuilder where = new StringBuilder(ledger ? " WHERE 1=1" : " WHERE r.request_type='CUSTOMER_DEPOSIT'");
        List<Object> args = new ArrayList<>();
        if (customerId != null) { where.append(" AND ").append(prefix).append(".customer_id=?"); args.add(customerId); }
        if (fundSource != null) { where.append(" AND r.fund_source=?"); args.add(fundSource); }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOCATE(?,c.customer_name)>0 OR LOCATE(?,c.customer_code)>0 OR LOCATE(?,COALESCE(r.operation_no,''))>0)");
            for (int i=0;i<3;i++) args.add(keyword.trim());
        }
        if (status != null) {
            where.append(ledger ? " AND (CASE WHEN EXISTS(SELECT 1 FROM customer_fund_ledger rev WHERE rev.reverses_ledger_id=l.id) THEN 'REVERSED' ELSE 'APPROVED' END)=?" : " AND r.status=?"); args.add(status);
        }
        if (direction != null) { where.append(ledger ? " AND l.direction=?" : " AND (CASE WHEN r.amount<0 THEN 'OUT' ELSE 'IN' END)=?"); args.add(direction); }
        String time = ledger ? "l.operated_at" : "r.submitted_at";
        if (from != null) { where.append(" AND ").append(time).append(">=?"); args.add(from.atStartOfDay()); }
        if (to != null) { where.append(" AND ").append(time).append("<?"); args.add(to.plusDays(1).atStartOfDay()); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + join + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        if (!exportAll) { paged.add(10); paged.add((Math.max(1,page)-1)*10); }
        List<Map<String,Object>> items = rows(select+join+where+" ORDER BY "+time+" DESC,"+prefix+".id DESC"+(exportAll ? "" : " LIMIT ? OFFSET ?"),paged);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items",items); result.put("total",total); result.put("page",Math.max(1,page)); result.put("pageSize",10);
        if (!ledger) {
            List<Map<String,Object>> totals = rows("""
                    SELECT COALESCE(SUM(CASE WHEN r.status='PENDING' AND r.amount>0 THEN r.amount ELSE 0 END),0) AS `pendingReceipt`,
                           COALESCE(SUM(CASE WHEN r.status='PENDING' AND r.amount<0 THEN -r.amount ELSE 0 END),0) AS `pendingRefund`,
                           COALESCE(SUM(CASE WHEN r.status='APPROVED' AND r.confirmed_amount>0 THEN r.confirmed_amount ELSE 0 END),0) AS `confirmedReceipt`,
                           COALESCE(SUM(CASE WHEN r.status='APPROVED' AND r.confirmed_amount<0 THEN -r.confirmed_amount ELSE 0 END),0) AS `confirmedRefund`
                    """+join+where,args);
            result.put("summary",totals.get(0));
        }
        return result;
    }

    private List<Map<String,Object>> rows(String sql, List<Object> parameters) {
        return jdbc.query(sql,(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();
            for(int i=1;i<=rs.getMetaData().getColumnCount();i++) {
                String name=rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
                Object value=rs.getObject(i);
                row.put(FIELD_NAMES.getOrDefault(name,name),value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : value);
            }
            return row;
        },parameters.toArray());
    }
}

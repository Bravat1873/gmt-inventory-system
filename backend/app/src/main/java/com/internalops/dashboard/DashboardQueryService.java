package com.internalops.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardQueryService {
    private final JdbcTemplate jdbc;

    public DashboardQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardSummary summary() {
        return new DashboardSummary(
                count("SELECT COUNT(*) FROM sales_order WHERE status='PENDING_CUSTOMER_PAYMENT'"),
                count("SELECT COUNT(*) FROM sales_order WHERE status='PENDING_STOCK'"),
                count("SELECT COUNT(*) FROM purchase_order WHERE status='PENDING_SUPPLIER_PAYMENT'"),
                count("SELECT COUNT(*) FROM sales_order WHERE status='READY_TO_SHIP'"));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}

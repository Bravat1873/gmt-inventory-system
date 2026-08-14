package com.internalops.customerfund;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerFundSchemaTest {
    @Test
    void migrationDefinesAccountApprovalLedgerAndAuditConstraints() throws Exception {
        String sql = new ClassPathResource("db/migration/V43__customer_funds_management.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        for (String table : new String[]{"customer_fund_account", "customer_fund_request", "customer_fund_ledger"}) {
            assertTrue(sql.contains("CREATE TABLE " + table), "缺少客户资金表 " + table);
        }
        for (String fragment : new String[]{
                "balance DECIMAL(19,4) NOT NULL DEFAULT 0",
                "CONSTRAINT ck_fund_account_non_negative CHECK (balance >= 0)",
                "request_type VARCHAR(30) NOT NULL",
                "status VARCHAR(20) NOT NULL DEFAULT 'PENDING'",
                "reviewed_by BIGINT",
                "direction VARCHAR(10) NOT NULL",
                "balance_before DECIMAL(19,4) NOT NULL",
                "balance_after DECIMAL(19,4) NOT NULL",
                "reverses_ledger_id BIGINT",
                "CONSTRAINT uk_fund_ledger_request UNIQUE (request_id)",
                "INDEX idx_fund_ledger_customer_time"
        }) {
            assertTrue(sql.contains(fragment), "客户资金迁移缺少 " + fragment);
        }
    }
}

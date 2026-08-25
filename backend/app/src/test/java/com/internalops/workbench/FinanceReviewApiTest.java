package com.internalops.workbench;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/finance-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class FinanceReviewApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final Cookie session = new Cookie("OPS_SESSION", "finance-test-token");

    @Test
    void returnsCamelCaseReviewFieldsForPendingSalesReceipt() throws Exception {
        jdbc.update("""
                UPDATE customer_receipt
                SET review_status='PENDING',
                    payment_method='银行转账',
                    payment_remark='待复核收款',
                    received_at=TIMESTAMP '2026-08-25 10:00:00'
                WHERE id=12
                """);

        mvc.perform(get("/api/finance/orders/SALES/10/records").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].paymentMethod").value("银行转账"))
                .andExpect(jsonPath("$.data[0].paymentRemark").value("待复核收款"))
                .andExpect(jsonPath("$.data[0].occurredAt").exists())
                .andExpect(jsonPath("$.data[0].reviewStatus").value("PENDING"));
    }

    @Test
    void approvingPaymentStoresConfirmedAmountWithoutOverwritingOriginalAmount() throws Exception {
        jdbc.update("""
                UPDATE supplier_payment
                SET amount=260.00,
                    confirmed_amount=NULL,
                    review_status='PENDING',
                    payment_method='银行转账'
                WHERE id=21
                """);

        mvc.perform(post("/api/finance/orders/payments/21/review").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"approved":true,"confirmedAmount":250.00,"reviewRemark":"按到账金额确认"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.confirmedAmount").value(250.0));

        Map<String, Object> row = jdbc.queryForMap("SELECT amount,confirmed_amount,review_status FROM supplier_payment WHERE id=21");
        assertThat(row.get("amount")).isEqualTo(new java.math.BigDecimal("260.00"));
        assertThat(row.get("confirmed_amount")).isEqualTo(new java.math.BigDecimal("250.00"));
        assertThat(row.get("review_status")).isEqualTo("APPROVED");
    }
}

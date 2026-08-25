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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/finance-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class FinanceInvoiceApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final Cookie session = new Cookie("OPS_SESSION", "finance-test-token");

    @Test
    void invoiceTablesExposeFinanceReviewColumns() {
        assertThatCode(() -> jdbc.queryForList("""
                SELECT confirmed_invoice_no, confirmed_amount, review_status
                FROM sales_invoice
                WHERE 1 = 0
                """)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.queryForList("""
                SELECT confirmed_invoice_no, confirmed_amount, review_status
                FROM purchase_invoice
                WHERE 1 = 0
                """)).doesNotThrowAnyException();
    }

    @Test
    void listsAllInvoicesForOneOrder() throws Exception {
        mvc.perform(get("/api/finance/orders/SALES/10/invoices").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].invoiceNo").value("XS-F-002"))
                .andExpect(jsonPath("$.data[1].invoiceNo").value("XS-F-001"));
    }

    @Test
    void createsAnotherInvoiceWithoutOverwritingHistory() throws Exception {
        mvc.perform(post("/api/finance/orders/SALES/10/invoices").cookie(session)
                        .contentType("application/json")
                        .content("""
                                {"invoiceNo":"XS-F-003","invoiceDate":"2026-08-12","taxInclusiveAmount":10.50,"remark":"补开发票"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceNo").value("XS-F-003"));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice WHERE sales_order_id=10", Integer.class);
        assertThat(count).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice WHERE invoice_no='XS-F-001'", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletesOnlyTheSelectedInvoice() throws Exception {
        mvc.perform(delete("/api/finance/orders/SALES/10/invoices/31").cookie(session))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice WHERE sales_order_id=10", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT invoice_no FROM sales_invoice WHERE sales_order_id=10", String.class)).isEqualTo("XS-F-002");
    }
}

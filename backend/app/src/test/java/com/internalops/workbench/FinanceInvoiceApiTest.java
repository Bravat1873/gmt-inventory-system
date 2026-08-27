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
    private final Cookie adminSession = new Cookie("OPS_SESSION", "admin-test-token");

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
        mvc.perform(post("/api/finance/orders/SALES/10/invoices").cookie(adminSession)
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
    void financeUserCannotCreateInvoice() throws Exception {
        mvc.perform(post("/api/finance/orders/SALES/10/invoices").cookie(session)
                        .contentType("application/json")
                        .content("{\"invoiceNo\":\"XS-F-DENIED\",\"taxInclusiveAmount\":10.50}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无发票维护权限"));
    }

    @Test
    void financeUserCannotReviewInvoice() throws Exception {
        mvc.perform(post("/api/finance/orders/SALES/invoices/31/review").cookie(session)
                        .contentType("application/json")
                        .content("{\"approved\":true,\"confirmedAmount\":30.00,\"confirmedInvoiceNo\":\"XS-F-001\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无发票维护权限"));
    }

    @Test
    void approvingInvoiceStoresConfirmedInvoiceNumberAndAmount() throws Exception {
        mvc.perform(post("/api/finance/orders/PURCHASE/20/invoices").cookie(adminSession)
                        .contentType("application/json")
                        .content("""
                                {"invoiceNo":"CG-F-002","invoiceDate":"2026-08-25","taxInclusiveAmount":122.00,"remark":"业务补录"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"));

        Long invoiceId = jdbc.queryForObject("SELECT id FROM purchase_invoice WHERE invoice_no='CG-F-002'", Long.class);

        mvc.perform(post("/api/finance/orders/PURCHASE/invoices/{id}/review", invoiceId).cookie(adminSession)
                        .contentType("application/json")
                        .content("""
                                {"approved":true,"confirmedAmount":120.00,"confirmedInvoiceNo":"CG-F-FINAL-002","reviewRemark":"按票面确认"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.confirmedInvoiceNo").value("CG-F-FINAL-002"))
                .andExpect(jsonPath("$.data.confirmedAmount").value(120.0));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT invoice_no,confirmed_invoice_no,tax_inclusive_amount,confirmed_amount,review_status
                FROM purchase_invoice
                WHERE id=?
                """, invoiceId);
        assertThat(row.get("invoice_no")).isEqualTo("CG-F-002");
        assertThat(row.get("confirmed_invoice_no")).isEqualTo("CG-F-FINAL-002");
        assertThat(row.get("tax_inclusive_amount")).isEqualTo(new java.math.BigDecimal("122.00"));
        assertThat(row.get("confirmed_amount")).isEqualTo(new java.math.BigDecimal("120.00"));
        assertThat(row.get("review_status")).isEqualTo("APPROVED");
    }

    @Test
    void reviewingPurchaseInvoiceWithSameIdDoesNotChangeSalesInvoice() throws Exception {
        jdbc.update("""
                UPDATE sales_invoice
                SET review_status='PENDING', confirmed_invoice_no=NULL, confirmed_amount=NULL
                WHERE id=31
                """);
        jdbc.update("""
                INSERT INTO purchase_invoice(id,purchase_order_id,invoice_no,tax_inclusive_amount,review_status,created_by)
                VALUES(31,20,'CG-F-SAME-ID',55.00,'PENDING',9)
                """);

        mvc.perform(post("/api/finance/orders/PURCHASE/invoices/{id}/review", 31).cookie(adminSession)
                        .contentType("application/json")
                        .content("""
                                {"approved":true,"confirmedAmount":55.00,"confirmedInvoiceNo":"CG-F-SAME-ID-FINAL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"));

        assertThat(jdbc.queryForObject("SELECT review_status FROM purchase_invoice WHERE id=31", String.class)).isEqualTo("APPROVED");
        Map<String, Object> salesInvoice = jdbc.queryForMap("SELECT review_status,confirmed_invoice_no,confirmed_amount FROM sales_invoice WHERE id=31");
        assertThat(salesInvoice.get("review_status")).isEqualTo("PENDING");
        assertThat(salesInvoice.get("confirmed_invoice_no")).isNull();
        assertThat(salesInvoice.get("confirmed_amount")).isNull();
    }

    @Test
    void savesInvoiceWithoutAmountAndSummaryTreatsAmountAsZero() throws Exception {
        jdbc.update("DELETE FROM purchase_invoice WHERE purchase_order_id=20");

        mvc.perform(post("/api/finance/orders/PURCHASE/20/invoices").cookie(adminSession)
                        .contentType("application/json")
                        .content("""
                                {"invoiceNo":"CG-F-NO-AMOUNT","remark":"待补金额"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceNo").value("CG-F-NO-AMOUNT"));

        assertThat(jdbc.queryForObject("SELECT tax_inclusive_amount FROM purchase_invoice WHERE invoice_no='CG-F-NO-AMOUNT'", java.math.BigDecimal.class)).isNull();
        mvc.perform(get("/api/finance/orders/PURCHASE/20/review-summary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedInvoiceAmount").value(0));
    }

    @Test
    void deletesOnlyTheSelectedInvoice() throws Exception {
        mvc.perform(delete("/api/finance/orders/SALES/10/invoices/31").cookie(adminSession))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice WHERE sales_order_id=10", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT invoice_no FROM sales_invoice WHERE sales_order_id=10", String.class)).isEqualTo("XS-F-002");
    }
}

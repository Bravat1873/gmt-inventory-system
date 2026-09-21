package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/finance-workbench-schema.sql", config = @SqlConfig(encoding = "UTF-8"))
class FinanceWorkbenchQueryApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Autowired com.internalops.exporting.ExcelExportService exports;

    private final Cookie session = new Cookie("OPS_SESSION", "finance-test-token");

    @Test
    void unreviewedPurchaseDraftsAreAlsoExcludedFromFinanceExport() throws Exception {
        jdbc.update("UPDATE purchase_order SET status='DRAFT' WHERE id=20");
        String no=jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=20",String.class);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exports.summary("finance")))) {
            var sheet=book.getSheetAt(0);
            for(int i=1;i<=sheet.getLastRowNum();i++) org.assertj.core.api.Assertions.assertThat(sheet.getRow(i).getCell(0).getStringCellValue()).isNotEqualTo(no);
        }
    }

    @Test
    void receiptAdjustmentsUseConfirmedNetAmountsInFinanceListAndExcel() throws Exception {
        jdbc.update("UPDATE customer_receipt SET confirmed_amount=25,review_status='APPROVED' WHERE id=12");
        jdbc.update("INSERT INTO customer_receipt(id,sales_order_id,amount,confirmed_amount,review_status) VALUES(90,10,-5,-5,'APPROVED')");
        jdbc.update("INSERT INTO customer_receipt(id,sales_order_id,amount,review_status) VALUES(91,10,99,'PENDING')");
        mvc.perform(get("/api/workbench/finance").param("keyword","SO-F-001").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].settledAmount").value(20))
                .andExpect(jsonPath("$.data.items[0].outstandingAmount").value(80));
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exports.summary("finance")))) {
            var sheet = workbook.getSheetAt(0);
            var sales = java.util.stream.IntStream.rangeClosed(1,sheet.getLastRowNum()).mapToObj(sheet::getRow)
                    .filter(row -> "SO-F-001".equals(row.getCell(0).getStringCellValue())).findFirst().orElseThrow();
            org.assertj.core.api.Assertions.assertThat(new java.math.BigDecimal(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(sales.getCell(6)))).isEqualByComparingTo("20");
            org.assertj.core.api.Assertions.assertThat(new java.math.BigDecimal(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(sales.getCell(7)))).isEqualByComparingTo("80");
        }
    }

    @Test
    void listsAutomaticallyLinkedReceivablesAndPayablesWithBalances() throws Exception {
        mvc.perform(get("/api/workbench/finance").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].cashDirection").value("PAYABLE"))
                .andExpect(jsonPath("$.data.items[0].businessNo").value("PO-F-002"))
                .andExpect(jsonPath("$.data.items[0].amount").value(100))
                .andExpect(jsonPath("$.data.items[0].settledAmount").value(40))
                .andExpect(jsonPath("$.data.items[0].outstandingAmount").value(60))
                .andExpect(jsonPath("$.data.items[0].invoiceNos").value("CG-F-001"))
                .andExpect(jsonPath("$.data.items[0].status").value("待付款"))
                .andExpect(jsonPath("$.data.items[1].cashDirection").value("RECEIVABLE"))
                .andExpect(jsonPath("$.data.items[1].businessNo").value("SO-F-001"))
                .andExpect(jsonPath("$.data.items[1].amount").value(100))
                .andExpect(jsonPath("$.data.items[1].settledAmount").value(30))
                .andExpect(jsonPath("$.data.items[1].outstandingAmount").value(70))
                .andExpect(jsonPath("$.data.items[1].invoiceNos").value("XS-F-001,XS-F-002"))
                .andExpect(jsonPath("$.data.items[1].status").value("待收款"));
    }

    @Test
    void excludesUnreviewedPurchaseDraftsFromPayables() throws Exception {
        jdbc.update("UPDATE purchase_order SET status='DRAFT' WHERE id=20");

        mvc.perform(get("/api/workbench/finance").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].cashDirection").value("RECEIVABLE"))
                .andExpect(jsonPath("$.data.items[0].businessNo").value("SO-F-001"));
    }

    @Test
    void reviewSummaryShowsMoneyInvoiceTotalsAndDifferences() throws Exception {
        jdbc.update("""
                UPDATE supplier_payment
                SET amount=100.00,
                    confirmed_amount=95.00,
                    review_status='APPROVED'
                WHERE id=21
                """);
        jdbc.update("""
                UPDATE purchase_invoice
                SET tax_inclusive_amount=100.00,
                    confirmed_amount=90.00,
                    confirmed_invoice_no='CG-F-FINAL-001',
                    review_status='APPROVED'
                WHERE id=41
                """);

        mvc.perform(get("/api/finance/orders/PAYABLE/20/review-summary").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.confirmedMoneyAmount").value(95.0))
                .andExpect(jsonPath("$.data.confirmedInvoiceAmount").value(90.0))
                .andExpect(jsonPath("$.data.differenceAmount").value(5.0));
    }
}

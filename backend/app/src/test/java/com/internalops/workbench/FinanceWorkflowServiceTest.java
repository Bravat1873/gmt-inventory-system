package com.internalops.workbench;

import com.internalops.customerfund.CustomerFundService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceWorkflowServiceTest {
    @Test
    void receiptUsesTheSelectedPastOrFutureDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "READY_TO_SHIP", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), mock(CustomerFundService.class));
        LocalDate selectedDate = LocalDate.of(2026, 9, 20);

        Map<String, Object> result = service.receipt(7L, new FinanceActionRequest(new BigDecimal("100"), "银行转账", null, null, null, selectedDate));

        assertEquals(LocalDateTime.of(2026, 9, 20, 0, 0), result.get("receivedAt"));
    }
    @Test
    void receiptIsRecordedWithoutChangingAnOrderThatIsReadyToShip() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "READY_TO_SHIP", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), mock(CustomerFundService.class));

        Map<String, Object> result = service.receipt(7L, new FinanceActionRequest(new BigDecimal("100"), "银行转账", null, null, null, null));

        assertEquals("READY_TO_SHIP", result.get("status"));
        verify(jdbc, never()).update(contains("status='PENDING_SALES_INVOICE'"), any(), anyLong());
    }

    @Test
    void receiptUsesTheFullOrderAmountAfterTheOrderHasShipped() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "SHIPPED", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), mock(CustomerFundService.class));

        Map<String, Object> result = service.receipt(7L, new FinanceActionRequest(new BigDecimal("100"), "银行转账", null, null, null, null));

        assertEquals(new BigDecimal("100"), result.get("receivableAmount"));
        verify(jdbc).queryForObject(argThat(sql -> sql.contains("SUM(i.quantity * i.sale_price)") && !sql.contains("SHIPPED")), eq(BigDecimal.class), eq(7L));
    }

    @Test
    void recordsInvoiceInformationWithEachReceipt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "READY_TO_SHIP", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), mock(CustomerFundService.class));

        service.receipt(7L, new FinanceActionRequest(new BigDecimal("100"), "银行转账", "XS-202608-01", LocalDate.of(2026, 8, 24), "尾款", LocalDate.of(2026, 8, 24)));

        verify(jdbc).update(contains("invoice_no"), eq(7L), eq(new BigDecimal("100")), eq("银行转账"),
                eq("XS-202608-01"), eq(LocalDate.of(2026, 8, 24)), eq("尾款"), any(LocalDateTime.class));
    }

    @Test
    void allowsZeroAmountInvoiceSupplementWithoutDebitingCustomerFunds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CustomerFundService customerFunds = mock(CustomerFundService.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "READY_TO_SHIP", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), customerFunds);

        Map<String, Object> result = service.receipt(7L, new FinanceActionRequest(BigDecimal.ZERO, "银行转账", "XS-补录", null, null, LocalDate.of(2026, 8, 24)));

        assertEquals(BigDecimal.ZERO, result.get("outstandingAmount"));
        verify(customerFunds, never()).debitForOrder(anyLong(), any(), anyString());
        verify(jdbc).update(contains("customer_receipt"), eq(7L), eq(BigDecimal.ZERO), eq("银行转账"), eq("XS-补录"), isNull(), isNull(), any(LocalDateTime.class));
    }
}

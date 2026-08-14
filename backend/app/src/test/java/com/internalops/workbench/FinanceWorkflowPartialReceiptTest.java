package com.internalops.workbench;

import com.internalops.customerfund.CustomerFundService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceWorkflowPartialReceiptTest {
    @Test
    void receiptDebitsCustomerBalanceAndAllowsPartialAmountUpToOutstanding() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of(
                "status", "READY_TO_SHIP", "total_amount", new BigDecimal("100"),
                "customer_id", 1L, "order_no", "SO-001"));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        CustomerFundService funds = mock(CustomerFundService.class);
        when(funds.debitForOrder(eq(7L), eq(new BigDecimal("25")), anyString())).thenReturn(99L);
        when(funds.balance(1L)).thenReturn(new BigDecimal("475"));
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class), funds);

        Map<String, Object> result = service.receipt(7L,
                new FinanceActionRequest(new BigDecimal("25"), "银行转账", null, null, null, null));

        assertEquals(new BigDecimal("75"), result.get("outstandingAmount"));
        assertEquals(new BigDecimal("475"), result.get("customerBalance"));
        verify(funds).debitForOrder(eq(7L), eq(new BigDecimal("25")), contains("银行转账"));
    }
}
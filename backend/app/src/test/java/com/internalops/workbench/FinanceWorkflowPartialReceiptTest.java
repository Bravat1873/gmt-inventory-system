package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceWorkflowPartialReceiptTest {
    @Test
    void receiptAllowsAManualPartialAmountUpToTheOutstandingAmount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of("status", "READY_TO_SHIP", "total_amount", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("FROM sales_order_item"), eq(BigDecimal.class), eq(7L))).thenReturn(new BigDecimal("100"));
        when(jdbc.queryForObject(contains("FROM customer_receipt"), eq(BigDecimal.class), eq(7L))).thenReturn(BigDecimal.ZERO);
        FinanceWorkflowService service = new FinanceWorkflowService(jdbc, mock(InventoryAllocationService.class));

        Map<String, Object> result = service.receipt(7L, new FinanceActionRequest(new BigDecimal("25"), "银行转账", null, null, null, null));

        assertEquals(new BigDecimal("75"), result.get("outstandingAmount"));
    }
}

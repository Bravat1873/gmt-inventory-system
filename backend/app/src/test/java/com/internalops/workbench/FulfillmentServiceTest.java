package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentServiceTest {
    @Test
    void clearsOrderLineLockedQuantityAfterShippingAllRemainingStock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(startsWith("SELECT status FROM sales_order"), eq(String.class), eq(1L)))
                .thenReturn("READY_TO_SHIP");
        when(jdbc.queryForObject(startsWith("SELECT id FROM warehouse"), eq(Long.class)))
                .thenReturn(1L);
        when(jdbc.queryForList(startsWith("SELECT id,sku_id"), eq(1L))).thenReturn(List.of(Map.of(
                "id", 21L, "sku_id", 9L, "quantity", 10, "shipped_quantity", 4, "locked_quantity", 6
        )));
        when(jdbc.queryForMap(startsWith("SELECT id,actual_quantity"), eq(1L), eq(9L))).thenReturn(Map.of(
                "id", 71L, "actual_quantity", 8, "locked_quantity", 6, "in_transit_quantity", 0
        ));
        FulfillmentService service = new FulfillmentService(jdbc, mock(InventoryAllocationService.class));

        service.ship(1L, new FulfillmentService.ShippingRequest("物流", "TRACK-1", null));

        verify(jdbc).update(startsWith("UPDATE sales_order_item SET shipped_quantity=quantity,locked_quantity=0"), eq(21L));
    }
}

package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShipmentQuantityServiceTest {
    @Test
    void explainsReservedStockShortageByMaterialInsteadOfExposingInternalLineNumber() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(startsWith("SELECT status FROM sales_order"), eq(String.class), eq(1L))).thenReturn("READY_TO_SHIP");
        when(jdbc.queryForObject(startsWith("SELECT id FROM warehouse"), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForList(startsWith("SELECT i.id"), eq(1L))).thenReturn(List.of(Map.of(
                "id", 21L, "line_no", 10000, "sku_id", 9L, "customer_part_number", "SKU-9", "product_name", "测试锁",
                "quantity", 10, "shipped_quantity", 0, "locked_quantity", 2
        )));
        when(jdbc.queryForMap(startsWith("SELECT id,actual_quantity"), eq(1L), eq(9L))).thenReturn(Map.of(
                "id", 71L, "actual_quantity", 2, "locked_quantity", 2, "in_transit_quantity", 0
        ));
        ShipmentQuantityService service = new ShipmentQuantityService(jdbc, mock(InventoryAllocationService.class));

        Throwable failure = catchThrowable(() -> service.update(1L, new ShipmentQuantityRequest("测试地址", null, List.of(new ShipmentQuantityRequest.Item(10000, 3)))));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure).hasMessageContaining("SKU-9").hasMessageContaining("本单已备货 2");
        assertThat(failure.getMessage()).doesNotContain("第 10000 行");
    }
}

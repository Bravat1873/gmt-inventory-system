package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Sql(scripts = "/sales-command-schema.sql")
class SupplyDemandQueryServiceTest {
    @Autowired SupplyDemandQueryService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aggregatesDefaultWarehouseInventoryAndOutstandingUncancelledDemand() {
        jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,version) VALUES(1,1,10,3,4,0)");
        long openOrder = insertOrder("WAITING_STOCK", "SO-OPEN");
        insertItem(openOrder, 1, 8, 2);
        long shippedOrder = insertOrder("SHIPPED", "SO-SHIPPED");
        insertItem(shippedOrder, 1, 5, 5);
        long cancelledOrder = insertOrder("CANCELLED", "SO-CANCELLED");
        insertItem(cancelledOrder, 1, 20, 0);

        SupplyDemandQueryService.SupplyDemandSnapshot value = service.bySkuIds(List.of(1L)).get(1L);

        assertEquals(10, value.actualQuantity());
        assertEquals(7, value.availableQuantity());
        assertEquals(4, value.inTransitQuantity());
        assertEquals(6, value.pendingDeliveryQuantity());
        assertEquals(8, value.supplyDemandBalance());
        assertEquals(0, value.purchaseShortageQuantity());
    }

    @Test
    void returnsNegativeBalanceAndPurchaseShortageWithoutInventoryRow() {
        long order = insertOrder("WAITING_STOCK", "SO-SHORT");
        insertItem(order, 2, 9, 0);

        SupplyDemandQueryService.SupplyDemandSnapshot value = service.bySkuIds(List.of(2L)).get(2L);

        assertEquals(0, value.actualQuantity());
        assertEquals(0, value.availableQuantity());
        assertEquals(0, value.inTransitQuantity());
        assertEquals(9, value.pendingDeliveryQuantity());
        assertEquals(-9, value.supplyDemandBalance());
        assertEquals(9, value.purchaseShortageQuantity());
    }

    private long insertOrder(String status, String orderNo) {
        jdbc.update("INSERT INTO sales_order(order_no,customer_id,status,total_amount,order_date) VALUES(?,1,?,0,CURRENT_DATE)", orderNo, status);
        return jdbc.queryForObject("SELECT id FROM sales_order WHERE order_no=?", Long.class, orderNo);
    }

    private void insertItem(long orderId, long skuId, int quantity, int shippedQuantity) {
        jdbc.update("INSERT INTO sales_order_item(sales_order_id,line_no,sku_id,quantity,shipped_quantity,locked_quantity,uncovered_quantity,sale_price) VALUES(?,10000,?,?,?,0,0,1)",
                orderId, skuId, quantity, shippedQuantity);
    }
}

package com.internalops.workbench;

/** Shipment progress for a sales order (outer table alias o), independent of its workflow state. */
public final class OrderShipmentStatus {
    private OrderShipmentStatus() {}

    public static final String SQL = """
            (SELECT CASE
                WHEN COALESCE(MAX(line.shipped_quantity), 0) <= 0 THEN '未发货'
                WHEN MAX(CASE WHEN COALESCE(line.shipped_quantity, 0) < line.quantity THEN 1 ELSE 0 END) = 1 THEN '部分未发货'
                ELSE '已发完货'
             END
             FROM sales_order_item line
             WHERE line.sales_order_id = o.id AND line.quantity > 0)
            """;
}

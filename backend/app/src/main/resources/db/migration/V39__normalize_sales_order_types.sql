UPDATE sales_order SET order_type = '工程订单'
WHERE LOWER(TRIM(order_type)) = 'project' OR TRIM(order_type) = '工程订单';

UPDATE sales_order SET order_type = '零售订单'
WHERE LOWER(TRIM(order_type)) IN ('retail', 'sales') OR TRIM(order_type) IN ('销售', '零售订单');

UPDATE sales_order SET order_type = '前置订单' WHERE TRIM(order_type) = '前置订单';

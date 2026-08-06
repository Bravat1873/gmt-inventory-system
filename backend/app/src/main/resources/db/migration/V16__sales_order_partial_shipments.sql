ALTER TABLE sales_order_item
    ADD COLUMN shipped_quantity INT NOT NULL DEFAULT 0 AFTER quantity;

UPDATE sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
SET soi.shipped_quantity = soi.quantity
WHERE so.status = 'SHIPPED';

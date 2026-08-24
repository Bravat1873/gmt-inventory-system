ALTER TABLE sales_order_item DROP CHECK ck_sales_item_qty;

ALTER TABLE sales_order_item
    ADD CONSTRAINT ck_sales_item_qty CHECK (quantity <> 0);

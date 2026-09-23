ALTER TABLE customer_fund_request DROP CHECK ck_fund_request_amount;
ALTER TABLE customer_fund_request ADD CONSTRAINT ck_fund_request_amount
    CHECK ((request_type = 'CUSTOMER_DEPOSIT' AND amount <> 0)
        OR (request_type = 'AFTER_SALES_REFUND' AND amount > 0));

ALTER TABLE after_sales_return_line ADD COLUMN return_unit_price DECIMAL(19,4) NULL;
UPDATE after_sales_return_line r
JOIN sales_order_item i ON i.id = r.sales_order_item_id
SET r.return_unit_price = i.sale_price;

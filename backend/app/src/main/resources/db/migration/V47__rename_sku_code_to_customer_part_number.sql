ALTER TABLE sku RENAME COLUMN sku_code TO customer_part_number;

ALTER TABLE after_sales_return_line RENAME COLUMN sku_code TO customer_part_number;

ALTER TABLE after_sales_replacement_line RENAME COLUMN sku_code TO customer_part_number;

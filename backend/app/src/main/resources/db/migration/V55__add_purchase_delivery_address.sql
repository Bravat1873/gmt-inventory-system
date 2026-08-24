ALTER TABLE purchase_order
    ADD COLUMN delivery_address VARCHAR(1000) NULL AFTER expected_arrival_date;

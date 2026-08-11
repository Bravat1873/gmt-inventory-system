ALTER TABLE sales_order
    ADD COLUMN business_contact_name VARCHAR(100) NULL,
    ADD COLUMN business_contact_phone VARCHAR(60) NULL,
    ADD COLUMN order_contact_name VARCHAR(100) NULL,
    ADD COLUMN order_contact_phone VARCHAR(60) NULL,
    ADD COLUMN finance_contact_name VARCHAR(100) NULL,
    ADD COLUMN finance_contact_phone VARCHAR(60) NULL;

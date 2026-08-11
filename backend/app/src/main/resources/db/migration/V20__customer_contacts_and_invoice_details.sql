ALTER TABLE customer
    ADD COLUMN business_contact_name VARCHAR(100) NULL AFTER address,
    ADD COLUMN business_contact_phone VARCHAR(40) NULL AFTER business_contact_name,
    ADD COLUMN order_contact_name VARCHAR(100) NULL AFTER business_contact_phone,
    ADD COLUMN order_contact_phone VARCHAR(40) NULL AFTER order_contact_name,
    ADD COLUMN finance_contact_name VARCHAR(100) NULL AFTER order_contact_phone,
    ADD COLUMN finance_contact_phone VARCHAR(40) NULL AFTER finance_contact_name,
    ADD COLUMN invoice_title VARCHAR(200) NULL AFTER finance_contact_phone,
    ADD COLUMN taxpayer_id VARCHAR(100) NULL AFTER invoice_title,
    ADD COLUMN invoice_address VARCHAR(500) NULL AFTER taxpayer_id,
    ADD COLUMN invoice_phone VARCHAR(40) NULL AFTER invoice_address,
    ADD COLUMN bank_name VARCHAR(200) NULL AFTER invoice_phone,
    ADD COLUMN bank_account VARCHAR(100) NULL AFTER bank_name;

UPDATE customer SET
    business_contact_name=COALESCE(business_contact_name,contact_name),
    business_contact_phone=COALESCE(business_contact_phone,phone),
    order_contact_name=COALESCE(order_contact_name,contact_name),
    order_contact_phone=COALESCE(order_contact_phone,phone);

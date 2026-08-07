CREATE INDEX idx_supplier_payment_purchase ON supplier_payment (purchase_order_id, paid_at);
ALTER TABLE supplier_payment DROP INDEX uk_supplier_payment_order;

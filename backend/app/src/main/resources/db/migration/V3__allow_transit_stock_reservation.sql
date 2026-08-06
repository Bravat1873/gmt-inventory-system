ALTER TABLE inventory_balance DROP CHECK ck_balance_locked;

ALTER TABLE inventory_balance
    ADD CONSTRAINT ck_balance_locked
        CHECK (locked_quantity >= 0 AND locked_quantity <= actual_quantity + in_transit_quantity);

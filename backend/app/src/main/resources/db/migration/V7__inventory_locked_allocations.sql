CREATE TABLE inventory_locked_allocation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    inventory_balance_id BIGINT NOT NULL,
    lock_source VARCHAR(30) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inventory_locked_source UNIQUE (inventory_balance_id, lock_source),
    CONSTRAINT fk_inventory_locked_balance FOREIGN KEY (inventory_balance_id) REFERENCES inventory_balance(id),
    CONSTRAINT ck_inventory_locked_quantity CHECK (quantity >= 0)
);

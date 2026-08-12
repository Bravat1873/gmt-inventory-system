CREATE TABLE after_sales_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sales_no VARCHAR(50) NOT NULL,
    sales_order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    after_sales_type VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    issue_description VARCHAR(1000) NOT NULL,
    application_date DATE NOT NULL,
    contact_name VARCHAR(100),
    contact_phone VARCHAR(60),
    delivery_address VARCHAR(500),
    remark VARCHAR(1000),
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_after_sales_no UNIQUE (after_sales_no),
    CONSTRAINT fk_after_sales_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_after_sales_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    INDEX idx_after_sales_updated (updated_at),
    INDEX idx_after_sales_status_updated (status, updated_at)
);

CREATE TABLE after_sales_return_line (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sales_order_id BIGINT NOT NULL,
    sales_order_item_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    sku_code VARCHAR(80), product_name VARCHAR(200), model VARCHAR(100), configuration VARCHAR(1000), unit VARCHAR(20),
    requested_quantity INT NOT NULL,
    received_quantity INT NOT NULL DEFAULT 0,
    good_quantity INT NOT NULL DEFAULT 0,
    defective_quantity INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_after_sales_return_order FOREIGN KEY (after_sales_order_id) REFERENCES after_sales_order(id),
    CONSTRAINT fk_after_sales_return_sales_item FOREIGN KEY (sales_order_item_id) REFERENCES sales_order_item(id),
    CONSTRAINT fk_after_sales_return_sku FOREIGN KEY (sku_id) REFERENCES sku(id),
    CONSTRAINT ck_after_sales_return_requested CHECK (requested_quantity > 0),
    CONSTRAINT ck_after_sales_return_received CHECK (received_quantity >= 0),
    CONSTRAINT ck_after_sales_return_good CHECK (good_quantity >= 0),
    CONSTRAINT ck_after_sales_return_defective CHECK (defective_quantity >= 0),
    UNIQUE KEY uk_after_sales_return_item (after_sales_order_id, sales_order_item_id)
);

CREATE TABLE after_sales_replacement_line (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sales_order_id BIGINT NOT NULL,
    return_line_id BIGINT,
    sku_id BIGINT NOT NULL,
    sku_code VARCHAR(80), product_name VARCHAR(200), model VARCHAR(100), configuration VARCHAR(1000), unit VARCHAR(20),
    planned_quantity INT NOT NULL,
    shipped_quantity INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_after_sales_replacement_order FOREIGN KEY (after_sales_order_id) REFERENCES after_sales_order(id),
    CONSTRAINT fk_after_sales_replacement_return FOREIGN KEY (return_line_id) REFERENCES after_sales_return_line(id),
    CONSTRAINT fk_after_sales_replacement_sku FOREIGN KEY (sku_id) REFERENCES sku(id),
    CONSTRAINT ck_after_sales_replacement_planned CHECK (planned_quantity > 0),
    CONSTRAINT ck_after_sales_replacement_shipped CHECK (shipped_quantity >= 0)
);

CREATE TABLE after_sales_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    after_sales_order_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    operated_by BIGINT,
    operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_after_sales_event_order FOREIGN KEY (after_sales_order_id) REFERENCES after_sales_order(id),
    INDEX idx_after_sales_event_order_time (after_sales_order_id, operated_at)
);

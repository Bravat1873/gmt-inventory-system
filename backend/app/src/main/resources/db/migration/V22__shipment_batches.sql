CREATE TABLE sales_shipment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shipment_no VARCHAR(50) NOT NULL,
    sales_order_id BIGINT NOT NULL,
    delivery_address VARCHAR(500) NOT NULL,
    operator_name VARCHAR(100) NOT NULL,
    shipped_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    remark VARCHAR(1000),
    CONSTRAINT uk_sales_shipment_no UNIQUE (shipment_no),
    CONSTRAINT fk_sales_shipment_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id),
    INDEX idx_sales_shipment_order_time (sales_order_id,shipped_at,id)
);

CREATE TABLE sales_shipment_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sales_shipment_id BIGINT NOT NULL,
    sales_order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    CONSTRAINT uk_sales_shipment_item UNIQUE (sales_shipment_id,sales_order_item_id),
    CONSTRAINT fk_sales_shipment_item_shipment FOREIGN KEY (sales_shipment_id) REFERENCES sales_shipment(id),
    CONSTRAINT fk_sales_shipment_item_order_item FOREIGN KEY (sales_order_item_id) REFERENCES sales_order_item(id),
    CONSTRAINT ck_sales_shipment_item_quantity CHECK (quantity > 0),
    INDEX idx_sales_shipment_item_order_item (sales_order_item_id)
);

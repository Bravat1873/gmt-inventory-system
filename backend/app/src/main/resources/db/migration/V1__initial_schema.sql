CREATE TABLE customer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_code VARCHAR(40) NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(100), phone VARCHAR(40), address VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_code UNIQUE (customer_code)
);

CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(80) NOT NULL, password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE warehouse (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    warehouse_code VARCHAR(40) NOT NULL, warehouse_name VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_warehouse_code UNIQUE (warehouse_code)
);

CREATE TABLE supplier (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_code VARCHAR(40) NOT NULL, supplier_name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(100), phone VARCHAR(40), bank_account VARCHAR(120), enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_supplier_code UNIQUE (supplier_code)
);

CREATE TABLE sku (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku_code VARCHAR(80) NOT NULL, model VARCHAR(100), product_name VARCHAR(200) NOT NULL,
    color VARCHAR(80), lock_body VARCHAR(80), product_version VARCHAR(80), configuration VARCHAR(1000), unit VARCHAR(20) NOT NULL DEFAULT '件',
    current_cost DECIMAL(18,4), enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sku_code UNIQUE (sku_code)
);

CREATE TABLE sku_supplier_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sku_id BIGINT NOT NULL, supplier_id BIGINT NOT NULL,
    purchase_price DECIMAL(18,4) NOT NULL, moq INT NOT NULL, lead_time_days INT NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sku_supplier_config_sku UNIQUE (sku_id),
    CONSTRAINT fk_ssc_sku FOREIGN KEY (sku_id) REFERENCES sku(id), CONSTRAINT fk_ssc_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id),
    CONSTRAINT ck_ssc_moq CHECK (moq > 0), CONSTRAINT ck_ssc_price CHECK (purchase_price >= 0)
);

CREATE TABLE sales_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(50) NOT NULL, external_order_no VARCHAR(100), customer_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL, total_amount DECIMAL(18,2) NOT NULL, receipt_confirmed_at DATETIME(3), shipped_at DATETIME(3),
    carrier VARCHAR(100), tracking_no VARCHAR(100), shipping_remark VARCHAR(500),
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sales_order_no UNIQUE (order_no), CONSTRAINT uk_sales_external_no UNIQUE (external_order_no),
    CONSTRAINT fk_sales_order_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
);

CREATE TABLE sales_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sales_order_id BIGINT NOT NULL, line_no INT NOT NULL, sku_id BIGINT NOT NULL,
    quantity INT NOT NULL, locked_quantity INT NOT NULL DEFAULT 0, uncovered_quantity INT NOT NULL DEFAULT 0,
    sale_price DECIMAL(18,2) NOT NULL, cost_snapshot DECIMAL(18,4),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_sales_item_line UNIQUE (sales_order_id,line_no),
    CONSTRAINT fk_sales_item_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id), CONSTRAINT fk_sales_item_sku FOREIGN KEY (sku_id) REFERENCES sku(id),
    CONSTRAINT ck_sales_item_qty CHECK (quantity > 0), CONSTRAINT ck_sales_item_price CHECK (sale_price >= 0)
);

CREATE TABLE attachment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, business_type VARCHAR(50) NOT NULL, business_id BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL, storage_key VARCHAR(500) NOT NULL, content_type VARCHAR(100) NOT NULL, file_size BIGINT NOT NULL,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_attachment_business (business_type,business_id)
);

CREATE TABLE customer_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sales_order_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL,
    payment_method VARCHAR(40) NOT NULL, received_at DATETIME(3) NOT NULL, confirmed_by BIGINT NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_receipt_order UNIQUE (sales_order_id), CONSTRAINT fk_receipt_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id)
);

CREATE TABLE sales_invoice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sales_order_id BIGINT NOT NULL, invoice_no VARCHAR(100) NOT NULL,
    invoice_date DATE NOT NULL, tax_inclusive_amount DECIMAL(18,2) NOT NULL, created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_invoice_order UNIQUE (sales_order_id), CONSTRAINT uk_invoice_no UNIQUE (invoice_no),
    CONSTRAINT fk_invoice_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id)
);

CREATE TABLE inventory_balance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
    actual_quantity INT NOT NULL DEFAULT 0, locked_quantity INT NOT NULL DEFAULT 0, in_transit_quantity INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inventory_balance UNIQUE (warehouse_id,sku_id),
    CONSTRAINT fk_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id), CONSTRAINT fk_balance_sku FOREIGN KEY (sku_id) REFERENCES sku(id),
    CONSTRAINT ck_balance_actual CHECK (actual_quantity >= 0), CONSTRAINT ck_balance_locked CHECK (locked_quantity >= 0 AND locked_quantity <= actual_quantity), CONSTRAINT ck_balance_transit CHECK (in_transit_quantity >= 0)
);

CREATE TABLE inventory_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
    transaction_type VARCHAR(40) NOT NULL, business_type VARCHAR(40) NOT NULL, business_no VARCHAR(80) NOT NULL,
    actual_delta INT NOT NULL DEFAULT 0, locked_delta INT NOT NULL DEFAULT 0, transit_delta INT NOT NULL DEFAULT 0,
    actual_before INT NOT NULL, actual_after INT NOT NULL, locked_before INT NOT NULL, locked_after INT NOT NULL, transit_before INT NOT NULL, transit_after INT NOT NULL,
    operated_by BIGINT, operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_inv_tx_sku_time (warehouse_id,sku_id,operated_at), INDEX idx_inv_tx_business (business_type,business_no),
    CONSTRAINT fk_tx_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id), CONSTRAINT fk_tx_sku FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE procurement_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, suggestion_no VARCHAR(50) NOT NULL, status VARCHAR(30) NOT NULL,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), confirmed_by BIGINT, confirmed_at DATETIME(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_suggestion_no UNIQUE (suggestion_no)
);

CREATE TABLE procurement_suggestion_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, suggestion_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, supplier_id BIGINT NOT NULL,
    shortage_quantity INT NOT NULL, suggested_quantity INT NOT NULL, confirmed_quantity INT, purchase_price DECIMAL(18,4) NOT NULL, expected_arrival_date DATE,
    CONSTRAINT uk_suggestion_sku UNIQUE (suggestion_id,sku_id), CONSTRAINT fk_psi_suggestion FOREIGN KEY (suggestion_id) REFERENCES procurement_suggestion(id),
    CONSTRAINT fk_psi_sku FOREIGN KEY (sku_id) REFERENCES sku(id), CONSTRAINT fk_psi_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE shortage_coverage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sales_order_item_id BIGINT NOT NULL, suggestion_item_id BIGINT NOT NULL,
    covered_quantity INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_active_shortage_coverage UNIQUE (sales_order_item_id,suggestion_item_id),
    CONSTRAINT fk_coverage_order_item FOREIGN KEY (sales_order_item_id) REFERENCES sales_order_item(id),
    CONSTRAINT fk_coverage_suggestion_item FOREIGN KEY (suggestion_item_id) REFERENCES procurement_suggestion_item(id)
);

CREATE TABLE purchase_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, purchase_no VARCHAR(50) NOT NULL, suggestion_id BIGINT NOT NULL, supplier_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL, total_amount DECIMAL(18,2) NOT NULL, expected_arrival_date DATE,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_purchase_no UNIQUE (purchase_no), CONSTRAINT uk_purchase_suggestion UNIQUE (suggestion_id),
    CONSTRAINT fk_po_suggestion FOREIGN KEY (suggestion_id) REFERENCES procurement_suggestion(id), CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES supplier(id)
);

CREATE TABLE purchase_order_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, purchase_order_id BIGINT NOT NULL, line_no INT NOT NULL, sku_id BIGINT NOT NULL,
    quantity INT NOT NULL, received_quantity INT NOT NULL DEFAULT 0, purchase_price DECIMAL(18,4) NOT NULL,
    CONSTRAINT uk_po_item_line UNIQUE (purchase_order_id,line_no), CONSTRAINT fk_poi_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id), CONSTRAINT fk_poi_sku FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE supplier_payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, purchase_order_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL,
    payment_method VARCHAR(40) NOT NULL, paid_at DATETIME(3) NOT NULL, confirmed_by BIGINT NOT NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_supplier_payment_order UNIQUE (purchase_order_id), CONSTRAINT fk_sp_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id)
);

CREATE TABLE goods_receipt (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, receipt_no VARCHAR(50) NOT NULL, purchase_order_id BIGINT NOT NULL,
    received_at DATETIME(3) NOT NULL, received_by BIGINT NOT NULL, status VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), CONSTRAINT uk_goods_receipt_no UNIQUE (receipt_no),
    CONSTRAINT fk_gr_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id)
);

CREATE TABLE goods_receipt_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, goods_receipt_id BIGINT NOT NULL, purchase_order_item_id BIGINT NOT NULL,
    accepted_quantity INT NOT NULL, rejected_quantity INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_gri_receipt FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id), CONSTRAINT fk_gri_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_item(id)
);

CREATE TABLE sku_cost_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sku_id BIGINT NOT NULL, old_cost DECIMAL(18,4), new_cost DECIMAL(18,4) NOT NULL,
    source_type VARCHAR(40) NOT NULL, source_no VARCHAR(80), effective_at DATETIME(3) NOT NULL, operated_by BIGINT,
    INDEX idx_cost_history_sku_time (sku_id,effective_at), CONSTRAINT fk_cost_history_sku FOREIGN KEY (sku_id) REFERENCES sku(id)
);

CREATE TABLE operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, business_type VARCHAR(50) NOT NULL, business_id BIGINT NOT NULL,
    action VARCHAR(60) NOT NULL, change_detail JSON, operated_by BIGINT, operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_operation_business (business_type,business_id,operated_at)
);

CREATE TABLE exception_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, case_no VARCHAR(50) NOT NULL, sales_order_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL, handling_note VARCHAR(1000), status VARCHAR(30) NOT NULL,
    created_by BIGINT, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_by BIGINT, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_exception_case_no UNIQUE (case_no), CONSTRAINT fk_exception_order FOREIGN KEY (sales_order_id) REFERENCES sales_order(id)
);

INSERT INTO warehouse (warehouse_code,warehouse_name,is_default,enabled) VALUES ('DEFAULT','默认仓库',TRUE,TRUE);

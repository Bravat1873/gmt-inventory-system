CREATE TABLE customer_contract (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    contract_no VARCHAR(80) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    remark VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_customer_contract_no UNIQUE(customer_id,contract_no),
    CONSTRAINT fk_customer_contract_customer FOREIGN KEY(customer_id) REFERENCES customer(id),
    CONSTRAINT ck_customer_contract_dates CHECK(end_date >= start_date)
);

CREATE TABLE customer_contract_price (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    contract_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    sale_price DECIMAL(18,4) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_customer_contract_sku UNIQUE(contract_id,sku_id),
    CONSTRAINT fk_contract_price_contract FOREIGN KEY(contract_id) REFERENCES customer_contract(id),
    CONSTRAINT fk_contract_price_sku FOREIGN KEY(sku_id) REFERENCES sku(id),
    CONSTRAINT ck_contract_sale_price CHECK(sale_price >= 0)
);

CREATE INDEX idx_customer_contract_dates ON customer_contract(customer_id,start_date,end_date,enabled);
CREATE INDEX idx_contract_price_sku ON customer_contract_price(sku_id,contract_id);

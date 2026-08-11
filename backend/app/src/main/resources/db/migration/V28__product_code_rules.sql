CREATE TABLE product_code_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(40) NOT NULL,
    code VARCHAR(20) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    remark VARCHAR(500),
    created_by BIGINT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_product_code_rule_category_code UNIQUE (category, code)
);

INSERT INTO product_code_rule(category,code,display_name,sort_order) VALUES
('BRAND','BR','STANLEY',10),
('SERIES','P90','P90',10),('SERIES','P50','P50',20),('SERIES','S70','S70',30),
('SERIES','S50','S50',40),('SERIES','F70','F70',50),('SERIES','F50','F50',60),('SERIES','D51','D51',70),
('BODY_COLOR','HGT','红钻铜',10),('BODY_COLOR','FGJ','富贵金',20),('BODY_COLOR','YZH','宇宙黑',30),
('BODY_COLOR','PBY','瀑布银',40),('BODY_COLOR','XKH','星空黑',50),('BODY_COLOR','BSL','宝石蓝',60),
('LOCK_TYPE','60','6068',10),('LOCK_TYPE','70','7068',20),('LOCK_TYPE','00','直舌',30),('LOCK_TYPE','01','钩舌',40),
('CONNECTIVITY','W','WiFi',10),('CONNECTIVITY','B','Bluetooth',20),('CONNECTIVITY','C','Cat.1/4G',30),
('CONNECTIVITY','N','NB-IoT',40),('CONNECTIVITY','O','单机',50),('CONNECTIVITY','M','MATTER',60),
('SALES_CHANNEL','P','平面',10),('SALES_CHANNEL','G','工程',20),('SALES_CHANNEL','E','电商',30),
('SALES_CHANNEL','K','商超',40),('SALES_CHANNEL','D','东铁专供',50),
('OPERATING_ENTITY','Z','珠海',10),('OPERATING_ENTITY','S','深圳',20),('OPERATING_ENTITY','G','广州',30),
('LANGUAGE','E','英文版',10),('LANGUAGE','C','中文版',20),('LANGUAGE','S','西班牙语',30);

ALTER TABLE sku
    DROP INDEX uk_sku_code,
    MODIFY COLUMN sku_code VARCHAR(80) NULL,
    ADD COLUMN product_code VARCHAR(80) NULL AFTER id,
    ADD COLUMN brand_rule_id BIGINT NULL,
    ADD COLUMN series_rule_id BIGINT NULL,
    ADD COLUMN body_color_rule_id BIGINT NULL,
    ADD COLUMN lock_type_rule_id BIGINT NULL,
    ADD COLUMN connectivity_rule_id BIGINT NULL,
    ADD COLUMN sales_channel_rule_id BIGINT NULL,
    ADD COLUMN operating_entity_rule_id BIGINT NULL,
    ADD COLUMN language_rule_id BIGINT NULL;

UPDATE sku SET product_code = CONCAT('OLD_', LPAD(id, 6, '0')) WHERE product_code IS NULL;

ALTER TABLE sku
    MODIFY COLUMN product_code VARCHAR(80) NOT NULL,
    ADD CONSTRAINT uk_sku_product_code UNIQUE (product_code),
    ADD CONSTRAINT fk_sku_brand_rule FOREIGN KEY (brand_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_series_rule FOREIGN KEY (series_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_body_color_rule FOREIGN KEY (body_color_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_lock_type_rule FOREIGN KEY (lock_type_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_connectivity_rule FOREIGN KEY (connectivity_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_sales_channel_rule FOREIGN KEY (sales_channel_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_operating_entity_rule FOREIGN KEY (operating_entity_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_language_rule FOREIGN KEY (language_rule_id) REFERENCES product_code_rule(id);

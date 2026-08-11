INSERT INTO product_code_rule(category,code,display_name,sort_order) VALUES
('DOOR_MODEL','N','对开门',10),('DOOR_MODEL','K','子母门',20),('DOOR_MODEL','M','单开门',30),('DOOR_MODEL','L','连套门（一门一套）',40),
('SECURITY_GRADE','J','甲',10),('SECURITY_GRADE','Y','乙',20),('SECURITY_GRADE','B','丙',30),('SECURITY_GRADE','D','丁',40),('SECURITY_GRADE','T','特级加强',50),
('BASE_MATERIAL','1','钢板/锌钢板',10),('BASE_MATERIAL','2','2.0不锈铁',20),('BASE_MATERIAL','3','304不锈钢',30),
('BASE_MATERIAL','4','316不锈钢',40),('BASE_MATERIAL','5','铜板/锌铜板',50),('BASE_MATERIAL','6','木板',60),
('BASE_MATERIAL','7','岩板',70),('BASE_MATERIAL','8','铝合金',80),('BASE_MATERIAL','9','特殊板材',90),
('THICKNESS','050','50mm',10),('THICKNESS','055','55mm',20),('THICKNESS','080','80mm',30),
('THICKNESS','085','85mm',40),('THICKNESS','100','100mm',50),('THICKNESS','120','120mm',60),
('FINISH_COLOR','A','花色A',10),('FINISH_COLOR','B','花色B',20),('FINISH_COLOR','C','花色C',30),
('FINISH_COLOR','D','花色D',40),('FINISH_COLOR','E','花色E',50),('FINISH_COLOR','F','花色F',60);

ALTER TABLE sku
    ADD COLUMN product_type VARCHAR(30) NOT NULL DEFAULT 'UNCLASSIFIED' AFTER product_code,
    ADD COLUMN door_model_rule_id BIGINT NULL,
    ADD COLUMN security_grade_rule_id BIGINT NULL,
    ADD COLUMN base_material_rule_id BIGINT NULL,
    ADD COLUMN thickness_rule_id BIGINT NULL,
    ADD COLUMN finish_color_rule_id BIGINT NULL,
    ADD CONSTRAINT fk_sku_door_model_rule FOREIGN KEY (door_model_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_security_grade_rule FOREIGN KEY (security_grade_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_base_material_rule FOREIGN KEY (base_material_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_thickness_rule FOREIGN KEY (thickness_rule_id) REFERENCES product_code_rule(id),
    ADD CONSTRAINT fk_sku_finish_color_rule FOREIGN KEY (finish_color_rule_id) REFERENCES product_code_rule(id);
